package pe.gustavo.contactoslite;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.test.InstrumentationTestCase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Runs only against a disposable emulator with contacts permissions granted. */
public class ContactsRepositoryTest extends InstrumentationTestCase {
  private ContentResolver resolver;
  private ContactsRepository repository;
  private String prefix;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    String fingerprint = Build.FINGERPRINT.toLowerCase();
    assertTrue(
        "Run only on a disposable emulator",
        fingerprint.contains("generic")
            || fingerprint.contains("emulator")
            || Build.MODEL.toLowerCase().contains("sdk")
            || Build.HARDWARE.toLowerCase().contains("goldfish")
            || Build.HARDWARE.toLowerCase().contains("ranchu"));
    assertEquals(
        "Grant READ_CONTACTS before running",
        PackageManager.PERMISSION_GRANTED,
        getInstrumentation()
            .getTargetContext()
            .checkSelfPermission(Manifest.permission.READ_CONTACTS));
    assertEquals(
        "Grant WRITE_CONTACTS before running",
        PackageManager.PERMISSION_GRANTED,
        getInstrumentation()
            .getTargetContext()
            .checkSelfPermission(Manifest.permission.WRITE_CONTACTS));
    resolver = getInstrumentation().getTargetContext().getContentResolver();
    repository = new ContactsRepository(getInstrumentation().getTargetContext());
    prefix = "ContactosLiteTest_" + UUID.randomUUID().toString().replace("-", "") + "_";
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      if (resolver != null && prefix != null) {
        List<Long> rawIds = new ArrayList<>();
        try (Cursor c =
            resolver.query(
                ContactsContract.Data.CONTENT_URI,
                new String[] {
                  ContactsContract.Data.RAW_CONTACT_ID,
                  ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME
                },
                ContactsContract.Data.MIMETYPE + "=?",
                new String[] {ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE},
                null)) {
          if (c != null)
            while (c.moveToNext()) {
              String name = c.getString(1);
              if (name != null && name.startsWith(prefix)) rawIds.add(c.getLong(0));
            }
        }
        for (long id : rawIds) {
          Uri exactRaw =
              ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id)
                  .buildUpon()
                  .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                  .build();
          resolver.delete(exactRaw, null, null);
        }
      }
    } finally {
      super.tearDown();
    }
  }

  public void testCreateSearchEditPreservesEmailAndDelete() throws Exception {
    String name = prefix + "Original";
    List<ContactModels.Phone> phones = Arrays.asList(phone("+15550001111"), phone("+15550002222"));
    long id =
        repository.create(name, phones, new ContactModels.AccountChoice(null, null, "Dispositivo"));
    ContactModels.Detail detail = repository.detail(id);
    assertNotNull(detail);
    ContactModels.Source source = findNamedSource(detail, name);
    assertNotNull("Created raw contact must be readable", source);
    assertFalse(source.readOnly);
    assertEquals(2, source.phones.size());
    assertTrue("Search by name should find created contact", containsId(repository.list(name), id));
    assertTrue(
        "Search by phone should find created contact",
        containsId(repository.list("15550001111"), id));
    assertFalse(
        "Unrelated search must not return it", containsId(repository.list(prefix + "absent"), id));

    ContentValues email = new ContentValues();
    email.put(ContactsContract.Data.RAW_CONTACT_ID, source.rawId);
    email.put(
        ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE);
    email.put(ContactsContract.CommonDataKinds.Email.ADDRESS, prefix + "@example.invalid");
    Uri emailUri = resolver.insert(ContactsContract.Data.CONTENT_URI, email);
    assertNotNull(emailUri);
    source = findNamedSource(repository.detail(id), name);
    assertNotNull(source);
    String changed = prefix + "Edited";
    repository.update(source, changed, Arrays.asList(phone("+15550003333")));
    ContactModels.Source edited = findNamedSource(repository.detail(id), changed);
    assertNotNull(edited);
    assertEquals(1, edited.phones.size());
    assertEquals("+15550003333", edited.phones.get(0).number);
    try (Cursor c =
        resolver.query(
            emailUri,
            new String[] {ContactsContract.CommonDataKinds.Email.ADDRESS},
            null,
            null,
            null)) {
      assertNotNull(c);
      assertTrue("Editing name and phones must preserve email", c.moveToFirst());
      assertEquals(prefix + "@example.invalid", c.getString(0));
    }
    repository.delete(edited);
    assertFalse(
        "Deleted contact should not be searchable", containsId(repository.list(changed), id));
  }

  public void testAggregatedSourcesStayIndependent() throws Exception {
    String first = prefix + "SourceOne", second = prefix + "SourceTwo";
    ContactModels.AccountChoice local = new ContactModels.AccountChoice(null, null, "Dispositivo");
    long firstId = repository.create(first, Arrays.asList(phone("+15550005111")), local);
    long secondId = repository.create(second, Arrays.asList(phone("+15550005222")), local);
    ContactModels.Source one = findNamedSource(repository.detail(firstId), first);
    ContactModels.Source two = findNamedSource(repository.detail(secondId), second);
    assertNotNull(one);
    assertNotNull(two);
    ContentValues link = new ContentValues();
    link.put(
        ContactsContract.AggregationExceptions.TYPE,
        ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER);
    link.put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, one.rawId);
    link.put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, two.rawId);
    assertTrue(
        resolver.update(ContactsContract.AggregationExceptions.CONTENT_URI, link, null, null) > 0);
    long aggregateId = contactIdForRaw(one.rawId);
    assertEquals(
        "Provider should aggregate the two synthetic sources",
        aggregateId,
        contactIdForRaw(two.rawId));
    ContactModels.Detail aggregate = repository.detail(aggregateId);
    one = findNamedSource(aggregate, first);
    assertNotNull(one);
    assertNotNull(findNamedSource(aggregate, second));
    repository.update(one, prefix + "SourceOneEdited", Arrays.asList(phone("+15550005333")));
    ContactModels.Source untouched =
        findNamedSource(repository.detail(contactIdForRaw(two.rawId)), second);
    assertNotNull("Editing one source must preserve the other name", untouched);
    assertEquals(1, untouched.phones.size());
    assertEquals("+15550005222", untouched.phones.get(0).number);
    ContactModels.Source changed =
        findNamedSource(repository.detail(contactIdForRaw(one.rawId)), prefix + "SourceOneEdited");
    assertNotNull(changed);
    repository.delete(changed);
    untouched = findNamedSource(repository.detail(contactIdForRaw(two.rawId)), second);
    assertNotNull("Deleting one source must preserve the other", untouched);
    assertEquals("+15550005222", untouched.phones.get(0).number);
  }

  public void testStaleSnapshotDoesNotOverwriteExternalChanges() throws Exception {
    String name = prefix + "Stale";
    long id =
        repository.create(
            name,
            Arrays.asList(phone("+15550006111")),
            new ContactModels.AccountChoice(null, null, "Dispositivo"));
    ContactModels.Source stale = findNamedSource(repository.detail(id), name);
    assertNotNull(stale);
    ContentValues external = new ContentValues();
    external.put(ContactsContract.Data.RAW_CONTACT_ID, stale.rawId);
    external.put(
        ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
    external.put(ContactsContract.CommonDataKinds.Phone.NUMBER, "+15550006222");
    assertNotNull(resolver.insert(ContactsContract.Data.CONTENT_URI, external));
    try {
      repository.update(stale, prefix + "StaleEdited", stale.phones);
      fail("A stale snapshot must not overwrite an external change");
    } catch (IllegalStateException expected) {
    }
    ContactModels.Source current =
        findNamedSource(repository.detail(contactIdForRaw(stale.rawId)), name);
    assertNotNull(current);
    assertEquals("External phone must survive", 2, current.phones.size());
  }

  private long contactIdForRaw(long rawId) {
    try (Cursor c =
        resolver.query(
            ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
            new String[] {ContactsContract.RawContacts.CONTACT_ID},
            null,
            null,
            null)) {
      assertNotNull(c);
      assertTrue(c.moveToFirst());
      assertFalse(c.isNull(0));
      return c.getLong(0);
    }
  }

  public void testSearchNormalizesNamesAndAllPhoneNumbers() throws Exception {
    String name = prefix + " José Álvarez";
    long id =
        repository.create(
            name,
            Arrays.asList(phone("+51 999 111 234"), phone("+51 (987) 654-321")),
            new ContactModels.AccountChoice(null, null, "Dispositivo"));
    assertTrue(
        "Name search should ignore case and accents",
        containsId(repository.list("JOSE ALVAREZ"), id));
    assertTrue("Name search should find a substring", containsId(repository.list("alva"), id));
    assertTrue(
        "Search should include secondary phone and ignore formatting",
        containsId(repository.list("987654"), id));
    assertTrue(
        "Formatted phone query should match normalized numbers",
        containsId(repository.list("987 654-321"), id));
    assertTrue(
        "Whitespace-only search should return the contact", containsId(repository.list("   "), id));
    assertFalse(
        "Unrelated query must not return the contact",
        containsId(repository.list(prefix + "no-match"), id));
  }

  public void testAppendPhonePreservesExisting() throws Exception {
    String name = prefix + "AppendPhone";
    long id =
        repository.create(
            name,
            Arrays.asList(phone("+15550009111")),
            new ContactModels.AccountChoice(null, null, "Dispositivo"));
    ContactModels.Source source = findNamedSource(repository.detail(id), name);
    assertNotNull(source);
    assertEquals(1, source.phones.size());
    long originalId = source.phones.get(0).id;
    List<ContactModels.Phone> appended = new ArrayList<>(source.phones);
    appended.add(phone("+15550009222"));
    repository.update(source, name, appended);
    source = findNamedSource(repository.detail(id), name);
    assertNotNull(source);
    assertEquals(2, source.phones.size());
    boolean originalFound = false, addedFound = false;
    for (ContactModels.Phone p : source.phones) {
      if (p.id == originalId && "+15550009111".equals(p.number)) originalFound = true;
      if ("+15550009222".equals(p.number)) addedFound = true;
    }
    assertTrue("Existing phone and data ID must be preserved", originalFound);
    assertTrue("New phone must be saved", addedFound);
    repository.update(source, name, source.phones);
    source = findNamedSource(repository.detail(id), name);
    assertNotNull(source);
    String changedName = prefix + "AppendPhoneEdited";
    repository.update(source, changedName, source.phones);
    source = findNamedSource(repository.detail(id), changedName);
    assertNotNull(source);
    assertEquals(2, source.phones.size());
    originalFound = false;
    for (ContactModels.Phone p : source.phones) if (p.id == originalId) originalFound = true;
    assertTrue("Name-only update must preserve existing phone ID", originalFound);
  }

  public void testExistingEmptyPhoneIsPreservedAndClearingNumberIsRejected() throws Exception {
    String name = prefix + "EmptyPhone";
    long id =
        repository.create(
            name,
            Arrays.asList(phone("+15550008111")),
            new ContactModels.AccountChoice(null, null, "Dispositivo"));
    ContactModels.Source source = findNamedSource(repository.detail(id), name);
    assertNotNull(source);
    ContentValues emptyRow = new ContentValues();
    emptyRow.put(ContactsContract.Data.RAW_CONTACT_ID, source.rawId);
    emptyRow.put(
        ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
    emptyRow.putNull(ContactsContract.CommonDataKinds.Phone.NUMBER);
    emptyRow.put(
        ContactsContract.CommonDataKinds.Phone.TYPE,
        ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM);
    emptyRow.put(ContactsContract.CommonDataKinds.Phone.LABEL, "synthetic-label");
    Uri emptyUri = resolver.insert(ContactsContract.Data.CONTENT_URI, emptyRow);
    assertNotNull(emptyUri);
    source = findNamedSource(repository.detail(id), name);
    assertNotNull(source);
    assertEquals(2, source.phones.size());
    String changedName = prefix + "EmptyPhoneEdited";
    repository.update(source, changedName, source.phones);
    try (Cursor c =
        resolver.query(
            emptyUri,
            new String[] {
              ContactsContract.CommonDataKinds.Phone.NUMBER,
              ContactsContract.CommonDataKinds.Phone.TYPE,
              ContactsContract.CommonDataKinds.Phone.LABEL
            },
            null,
            null,
            null)) {
      assertNotNull(c);
      assertTrue("An existing empty phone row must survive name edits", c.moveToFirst());
      assertTrue(c.isNull(0) || c.getString(0).isEmpty());
      assertEquals(ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM, c.getInt(1));
      assertEquals("synthetic-label", c.getString(2));
    }
    source = findNamedSource(repository.detail(id), changedName);
    assertNotNull(source);
    List<ContactModels.Phone> changedPhones = new ArrayList<>();
    for (ContactModels.Phone p : source.phones) {
      changedPhones.add(
          "+15550008111".equals(p.number) ? new ContactModels.Phone(p.id, "", p.type, p.label) : p);
    }
    try {
      repository.update(source, changedName, changedPhones);
      fail("Clearing an existing number must be rejected");
    } catch (IllegalArgumentException expected) {
    }
    ContactModels.Source unchanged = findNamedSource(repository.detail(id), changedName);
    boolean found = false;
    for (ContactModels.Phone p : unchanged.phones)
      if ("+15550008111".equals(p.number)) found = true;
    assertTrue("The original number must remain after rejection", found);
  }

  public void testProviderReadOnlyPhoneRejectsChangesAndDeletion() throws Exception {
    String name = prefix + "ProtectedPhone";
    long id =
        repository.create(
            name, new ArrayList<>(), new ContactModels.AccountChoice(null, null, "Dispositivo"));
    ContactModels.Source source = findNamedSource(repository.detail(id), name);
    assertNotNull(source);
    ContentValues row = new ContentValues();
    row.put(ContactsContract.Data.RAW_CONTACT_ID, source.rawId);
    row.put(
        ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
    row.put(ContactsContract.CommonDataKinds.Phone.NUMBER, "+15550007111");
    row.put(ContactsContract.Data.IS_READ_ONLY, 1);
    Uri syncData =
        ContactsContract.Data.CONTENT_URI
            .buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .build();
    assertNotNull(resolver.insert(syncData, row));
    source = findNamedSource(repository.detail(id), name);
    assertNotNull(source);
    assertEquals(1, source.phones.size());
    ContactModels.Phone protectedPhone = source.phones.get(0);
    assertTrue("Provider read-only flag must be visible", protectedPhone.readOnly);
    try {
      repository.update(
          source,
          name,
          Arrays.asList(
              new ContactModels.Phone(
                  protectedPhone.id, "+15550007222", protectedPhone.type, protectedPhone.label)));
      fail("Editing a protected phone must be rejected");
    } catch (IllegalStateException expected) {
    }
    try {
      repository.update(source, name, new ArrayList<>());
      fail("Removing a protected phone must be rejected");
    } catch (IllegalStateException expected) {
    }
    try {
      repository.delete(source);
      fail("Deleting a source containing protected data must be rejected");
    } catch (IllegalStateException expected) {
    }
    ContactModels.Source unchanged = findNamedSource(repository.detail(id), name);
    assertNotNull(unchanged);
    assertEquals("+15550007111", unchanged.phones.get(0).number);
  }

  public void testReadOnlySourceRejectsChanges() throws Exception {
    String name = prefix + "ReadOnly";
    long id =
        repository.create(
            name,
            Arrays.asList(phone("+15550004444")),
            new ContactModels.AccountChoice(null, null, "Dispositivo"));
    ContactModels.Source original = findNamedSource(repository.detail(id), name);
    assertNotNull(original);
    ContactModels.Source protectedSource =
        new ContactModels.Source(
            original.rawId,
            original.accountName,
            original.accountType,
            true,
            original.name,
            original.phones);
    try {
      repository.update(protectedSource, prefix + "Forbidden", original.phones);
      fail("A read-only source must reject updates");
    } catch (IllegalStateException expected) {
    }
    try {
      repository.delete(protectedSource);
      fail("A read-only source must reject deletion");
    } catch (IllegalStateException expected) {
    }
    assertNotNull(findNamedSource(repository.detail(id), name));
  }

  private ContactModels.Phone phone(String number) {
    return new ContactModels.Phone(
        0, number, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, null);
  }

  private static boolean containsId(List<ContactModels.Summary> list, long id) {
    for (ContactModels.Summary item : list) if (item.id == id) return true;
    return false;
  }

  private static ContactModels.Source findNamedSource(ContactModels.Detail detail, String name) {
    if (detail != null)
      for (ContactModels.Source source : detail.sources)
        if (name.equals(source.name)) return source;
    return null;
  }
}
