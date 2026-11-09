package pe.gustavo.contactoslite;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SyncAdapterType;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ContactsRepository {
  private static final Pattern MARKS = Pattern.compile("\\p{M}+");
  private final ContentResolver resolver;

  public ContactsRepository(Context context) {
    resolver = context.getApplicationContext().getContentResolver();
  }

  private Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    Cursor c = resolver.query(uri, projection, selection, args, sort);
    if (c == null) throw new IllegalStateException("No se pudo consultar contactos");
    return c;
  }

  private static String normalize(String text) {
    if (text == null) return "";
    return MARKS
        .matcher(Normalizer.normalize(text, Normalizer.Form.NFD))
        .replaceAll("")
        .toLowerCase(Locale.ROOT);
  }

  private static String digits(String text) {
    if (text == null) return "";
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch >= '0' && ch <= '9') result.append(ch);
    }
    return result.toString();
  }

  public List<ContactModels.Summary> list(String query) {
    List<ContactModels.Summary> result = new ArrayList<>();
    Map<Long, String> phones = new HashMap<>();
    Set<Long> phoneMatches = new HashSet<>();
    String term = normalize(query == null ? "" : query.trim());
    String numberTerm = digits(term);
    boolean numberSearch = !numberTerm.isEmpty() && term.matches("[0-9+() .-]+");
    try (Cursor p =
        query(
            Phone.CONTENT_URI,
            new String[] {Phone.CONTACT_ID, Phone.NUMBER},
            null,
            null,
            Phone.IS_SUPER_PRIMARY + " DESC, " + Phone.IS_PRIMARY + " DESC")) {
      while (p.moveToNext()) {
        long id = p.getLong(0);
        String number = p.getString(1);
        if (!phones.containsKey(id)) phones.put(id, number);
        if (numberSearch && digits(number).contains(numberTerm)) phoneMatches.add(id);
      }
    }
    try (Cursor c =
        query(
            Contacts.CONTENT_URI,
            new String[] {Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY},
            null,
            null,
            Contacts.SORT_KEY_PRIMARY + " COLLATE LOCALIZED ASC")) {
      while (c.moveToNext()) {
        long id = c.getLong(0);
        if (term.isEmpty()
            || normalize(c.getString(2)).contains(term)
            || phoneMatches.contains(id)) {
          result.add(
              new ContactModels.Summary(
                  id,
                  c.getString(1),
                  c.getString(2),
                  phones.containsKey(id) ? phones.get(id) : ""));
        }
      }
    }
    return result;
  }

  private Set<Long> readOnlyDataIds(long rawId) {
    Set<Long> ids = new HashSet<>();
    try (Cursor c =
        query(
            Data.CONTENT_URI,
            new String[] {Data._ID},
            Data.RAW_CONTACT_ID + "=? AND " + Data.IS_READ_ONLY + "!=0",
            new String[] {String.valueOf(rawId)},
            null)) {
      while (c.moveToNext()) ids.add(c.getLong(0));
    }
    return ids;
  }

  public ContactModels.Detail detail(long contactId) {
    String key, name;
    try (Cursor c =
        query(
            ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId),
            new String[] {Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY},
            null,
            null,
            null)) {
      if (!c.moveToFirst()) throw new IllegalStateException("El contacto ya no existe");
      key = c.getString(0);
      name = c.getString(1);
    }
    List<ContactModels.Source> sources = new ArrayList<>();
    Set<Long> writableIds = new HashSet<>();
    try (Cursor writable =
        query(
            RawContacts.CONTENT_URI,
            new String[] {RawContacts._ID},
            RawContacts.CONTACT_ID
                + "=? AND "
                + RawContacts.DELETED
                + "=0 AND "
                + RawContacts.RAW_CONTACT_IS_READ_ONLY
                + "=0",
            new String[] {String.valueOf(contactId)},
            null)) {
      while (writable.moveToNext()) writableIds.add(writable.getLong(0));
    }
    try (Cursor c =
        query(
            RawContacts.CONTENT_URI,
            new String[] {
              RawContacts._ID,
              RawContacts.ACCOUNT_NAME,
              RawContacts.ACCOUNT_TYPE,
              RawContacts.DISPLAY_NAME_PRIMARY,
              RawContacts.VERSION
            },
            RawContacts.CONTACT_ID + "=? AND " + RawContacts.DELETED + "=0",
            new String[] {String.valueOf(contactId)},
            null)) {
      while (c.moveToNext()) {
        long id = c.getLong(0);
        sources.add(
            new ContactModels.Source(
                id,
                c.getString(1),
                c.getString(2),
                !writableIds.contains(id),
                c.getString(3),
                java.util.Collections.emptyList(),
                c.getLong(4)));
      }
    }
    // Some providers allow IS_READ_ONLY in selections but reject it in projections.
    Set<Long> readOnlyIds = new HashSet<>();
    try (Cursor c =
        query(
            Data.CONTENT_URI,
            new String[] {Data._ID},
            Data.CONTACT_ID + "=? AND " + Data.IS_READ_ONLY + "!=0",
            new String[] {String.valueOf(contactId)},
            null)) {
      while (c.moveToNext()) readOnlyIds.add(c.getLong(0));
    }
    Map<Long, List<ContactModels.Phone>> phonesByRaw = new HashMap<>();
    try (Cursor p =
        query(
            Data.CONTENT_URI,
            new String[] {Data.RAW_CONTACT_ID, Data._ID, Phone.NUMBER, Phone.TYPE, Phone.LABEL},
            Data.CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?",
            new String[] {String.valueOf(contactId), Phone.CONTENT_ITEM_TYPE},
            null)) {
      while (p.moveToNext()) {
        long rawId = p.getLong(0);
        List<ContactModels.Phone> phones = phonesByRaw.get(rawId);
        if (phones == null) {
          phones = new ArrayList<>();
          phonesByRaw.put(rawId, phones);
        }
        phones.add(
            new ContactModels.Phone(
                p.getLong(1),
                p.getString(2),
                p.getInt(3),
                p.getString(4),
                readOnlyIds.contains(p.getLong(1))));
      }
    }
    for (int i = 0; i < sources.size(); i++) {
      ContactModels.Source source = sources.get(i);
      List<ContactModels.Phone> phones = phonesByRaw.get(source.rawId);
      if (phones == null) phones = java.util.Collections.emptyList();
      sources.set(
          i,
          new ContactModels.Source(
              source.rawId,
              source.accountName,
              source.accountType,
              source.readOnly,
              source.name,
              phones,
              source.version));
    }
    return new ContactModels.Detail(contactId, key, name, sources);
  }

  public List<ContactModels.AccountChoice> writableAccounts() {
    List<ContactModels.AccountChoice> choices = new ArrayList<>();
    choices.add(new ContactModels.AccountChoice(null, null, "Dispositivo"));
    Set<String> writableTypes = new HashSet<>();
    for (SyncAdapterType adapter : ContentResolver.getSyncAdapterTypes()) {
      if (ContactsContract.AUTHORITY.equals(adapter.authority) && adapter.supportsUploading())
        writableTypes.add(adapter.accountType);
    }
    Set<String> seen = new HashSet<>();
    try (Cursor c =
        query(
            RawContacts.CONTENT_URI,
            new String[] {RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE, RawContacts.DATA_SET},
            RawContacts.DELETED + "=0 AND " + RawContacts.RAW_CONTACT_IS_READ_ONLY + "=0",
            null,
            null)) {
      while (c.moveToNext()) {
        String name = c.getString(0), type = c.getString(1);
        if (name == null || type == null || c.getString(2) != null || !writableTypes.contains(type))
          continue;
        if (seen.add(type + "\u0000" + name))
          choices.add(new ContactModels.AccountChoice(name, type, name + " (" + type + ")"));
      }
    }
    return choices;
  }

  private static String validName(String name) {
    if (name == null || name.trim().isEmpty())
      throw new IllegalArgumentException("Escribe un nombre");
    return name.trim();
  }

  private static void validatePhones(List<ContactModels.Phone> phones, boolean allowExistingEmpty) {
    if (phones == null) throw new IllegalArgumentException("Lista de teléfonos inválida");
    Set<Long> ids = new HashSet<>();
    for (ContactModels.Phone p : phones) {
      if (p == null
          || ((p.number == null || p.number.trim().isEmpty())
              && (!allowExistingEmpty || p.id <= 0)))
        throw new IllegalArgumentException("El teléfono no puede estar vacío");
      if (p.id > 0 && !ids.add(p.id)) throw new IllegalArgumentException("Teléfono duplicado");
    }
  }

  private static ContentProviderOperation.Builder phoneValues(
      ContentProviderOperation.Builder b, ContactModels.Phone p) {
    return b.withValue(Phone.NUMBER, p.number.trim())
        .withValue(Phone.TYPE, p.type)
        .withValue(Phone.LABEL, p.label);
  }

  public long create(
      String name, List<ContactModels.Phone> phones, ContactModels.AccountChoice account)
      throws Exception {
    name = validName(name);
    validatePhones(phones, false);
    if (account == null || ((account.name == null) != (account.type == null)))
      throw new IllegalArgumentException("Cuenta inválida");
    boolean found = false;
    for (ContactModels.AccountChoice a : writableAccounts())
      if (java.util.Objects.equals(a.name, account.name)
          && java.util.Objects.equals(a.type, account.type)) found = true;
    if (!found) throw new IllegalStateException("La cuenta ya no está disponible");
    ArrayList<ContentProviderOperation> ops = new ArrayList<>();
    ops.add(
        ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_NAME, account.name)
            .withValue(RawContacts.ACCOUNT_TYPE, account.type)
            .build());
    ops.add(
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, 0)
            .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.DISPLAY_NAME, name)
            .build());
    for (ContactModels.Phone p : phones)
      ops.add(
          phoneValues(
                  ContentProviderOperation.newInsert(Data.CONTENT_URI)
                      .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                      .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE),
                  p)
              .build());
    ContentProviderResult[] results = resolver.applyBatch(ContactsContract.AUTHORITY, ops);
    long rawId = ContentUris.parseId(results[0].uri);
    try (Cursor c =
        query(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId),
            new String[] {RawContacts.CONTACT_ID},
            null,
            null,
            null)) {
      if (!c.moveToFirst() || c.isNull(0))
        throw new IllegalStateException("No se pudo localizar el contacto creado");
      return c.getLong(0);
    }
  }

  private long checkRaw(ContactModels.Source source) {
    if (source == null || source.readOnly || source.rawId <= 0)
      throw new IllegalStateException("Esta fuente es de solo lectura");
    try (Cursor c =
        query(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, source.rawId),
            new String[] {
              RawContacts.VERSION,
              RawContacts.ACCOUNT_NAME,
              RawContacts.ACCOUNT_TYPE,
              RawContacts.DELETED
            },
            RawContacts.RAW_CONTACT_IS_READ_ONLY + "=0",
            null,
            null)) {
      if (!c.moveToFirst()
          || c.getInt(3) != 0
          || !java.util.Objects.equals(c.getString(1), source.accountName)
          || !java.util.Objects.equals(c.getString(2), source.accountType))
        throw new IllegalStateException("La fuente cambió o ya no se puede editar");
      long version = c.getLong(0);
      if (source.version >= 0 && version != source.version)
        throw new IllegalStateException("El contacto cambió. Vuelve a abrirlo antes de guardar");
      return version;
    }
  }

  private void assertRaw(
      ArrayList<ContentProviderOperation> ops, ContactModels.Source source, long version) {
    ops.add(
        ContentProviderOperation.newAssertQuery(
                ContentUris.withAppendedId(RawContacts.CONTENT_URI, source.rawId))
            .withSelection(
                RawContacts.RAW_CONTACT_IS_READ_ONLY
                    + "=0 AND "
                    + RawContacts.DELETED
                    + "=0 AND "
                    + RawContacts.VERSION
                    + "=?",
                new String[] {String.valueOf(version)})
            .withExpectedCount(1)
            .build());
  }

  public void update(ContactModels.Source source, String name, List<ContactModels.Phone> phones)
      throws Exception {
    name = validName(name);
    validatePhones(phones, true);
    long version = checkRaw(source);
    Set<Long> readOnlyIds = readOnlyDataIds(source.rawId);
    Map<Long, Boolean> existing = new HashMap<>();
    long nameId = -1;
    boolean nameReadOnly = false;
    try (Cursor c =
        query(
            Data.CONTENT_URI,
            new String[] {Data._ID, Data.MIMETYPE},
            Data.RAW_CONTACT_ID + "=? AND (" + Data.MIMETYPE + "=? OR " + Data.MIMETYPE + "=?)",
            new String[] {
              String.valueOf(source.rawId),
              Phone.CONTENT_ITEM_TYPE,
              StructuredName.CONTENT_ITEM_TYPE
            },
            null)) {
      while (c.moveToNext()) {
        if (Phone.CONTENT_ITEM_TYPE.equals(c.getString(1)))
          existing.put(c.getLong(0), readOnlyIds.contains(c.getLong(0)));
        else if (nameId < 0) {
          nameId = c.getLong(0);
          nameReadOnly = readOnlyIds.contains(c.getLong(0));
        }
      }
    }
    ArrayList<ContentProviderOperation> ops = new ArrayList<>();
    assertRaw(ops, source, version);
    if (!java.util.Objects.equals(name, source.name)) {
      if (nameReadOnly) throw new IllegalStateException("El nombre es de solo lectura");
      if (nameId < 0)
        ops.add(
            ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValue(Data.RAW_CONTACT_ID, source.rawId)
                .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, name)
                .build());
      else
        ops.add(
            ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                .withSelection(
                    Data._ID
                        + "=? AND "
                        + Data.RAW_CONTACT_ID
                        + "=? AND "
                        + Data.IS_READ_ONLY
                        + "=0",
                    new String[] {String.valueOf(nameId), String.valueOf(source.rawId)})
                .withValue(StructuredName.DISPLAY_NAME, name)
                .withExpectedCount(1)
                .build());
    }
    Map<Long, ContactModels.Phone> snapshot = new HashMap<>();
    for (ContactModels.Phone p : source.phones) if (p.id > 0) snapshot.put(p.id, p);
    Set<Long> retained = new HashSet<>();
    for (ContactModels.Phone p : phones) {
      if (p.id > 0) {
        Boolean readOnly = existing.get(p.id);
        if (readOnly == null)
          throw new IllegalStateException("El teléfono ya no pertenece a esta fuente");
        ContactModels.Phone original = snapshot.get(p.id);
        if (original == null)
          throw new IllegalStateException("El teléfono no estaba en la versión abierta");
        retained.add(p.id);
        boolean empty = p.number == null || p.number.trim().isEmpty();
        boolean originalEmpty = original.number == null || original.number.trim().isEmpty();
        if (empty) {
          if (!originalEmpty
              || p.type != original.type
              || !java.util.Objects.equals(p.label, original.label))
            throw new IllegalArgumentException("Para quitar un teléfono, usa el botón Quitar");
          continue;
        }
        boolean unchanged =
            java.util.Objects.equals(p.number, original.number)
                && p.type == original.type
                && java.util.Objects.equals(p.label, original.label);
        if (unchanged) continue;
        if (readOnly) throw new IllegalStateException("El teléfono es de solo lectura");
        if (java.util.Objects.equals(p.number.trim(), original.number)
            && p.type == original.type
            && java.util.Objects.equals(p.label, original.label)) continue;
        ops.add(
            phoneValues(
                    ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                        .withSelection(
                            Data._ID
                                + "=? AND "
                                + Data.RAW_CONTACT_ID
                                + "=? AND "
                                + Data.MIMETYPE
                                + "=? AND "
                                + Data.IS_READ_ONLY
                                + "=0",
                            new String[] {
                              String.valueOf(p.id),
                              String.valueOf(source.rawId),
                              Phone.CONTENT_ITEM_TYPE
                            })
                        .withExpectedCount(1),
                    p)
                .build());
      } else
        ops.add(
            phoneValues(
                    ContentProviderOperation.newInsert(Data.CONTENT_URI)
                        .withValue(Data.RAW_CONTACT_ID, source.rawId)
                        .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE),
                    p)
                .build());
    }
    for (Map.Entry<Long, Boolean> e : existing.entrySet())
      if (snapshot.containsKey(e.getKey()) && !retained.contains(e.getKey())) {
        if (e.getValue())
          throw new IllegalStateException("No se puede eliminar un teléfono de solo lectura");
        ops.add(
            ContentProviderOperation.newDelete(Data.CONTENT_URI)
                .withSelection(
                    Data._ID
                        + "=? AND "
                        + Data.RAW_CONTACT_ID
                        + "=? AND "
                        + Data.MIMETYPE
                        + "=? AND "
                        + Data.IS_READ_ONLY
                        + "=0",
                    new String[] {
                      String.valueOf(e.getKey()),
                      String.valueOf(source.rawId),
                      Phone.CONTENT_ITEM_TYPE
                    })
                .withExpectedCount(1)
                .build());
      }
    resolver.applyBatch(ContactsContract.AUTHORITY, ops);
  }

  public void delete(ContactModels.Source source) throws Exception {
    long version = checkRaw(source);
    try (Cursor c =
        query(
            Data.CONTENT_URI,
            new String[] {Data._ID},
            Data.RAW_CONTACT_ID + "=? AND " + Data.IS_READ_ONLY + "!=0",
            new String[] {String.valueOf(source.rawId)},
            null)) {
      if (c.moveToFirst())
        throw new IllegalStateException("La fuente contiene datos de solo lectura");
    }
    ArrayList<ContentProviderOperation> ops = new ArrayList<>();
    assertRaw(ops, source, version);
    ops.add(
        ContentProviderOperation.newAssertQuery(Data.CONTENT_URI)
            .withSelection(
                Data.RAW_CONTACT_ID + "=? AND " + Data.IS_READ_ONLY + "!=0",
                new String[] {String.valueOf(source.rawId)})
            .withExpectedCount(0)
            .build());
    ops.add(
        ContentProviderOperation.newDelete(
                ContentUris.withAppendedId(RawContacts.CONTENT_URI, source.rawId))
            .withSelection(
                RawContacts.RAW_CONTACT_IS_READ_ONLY + "=0 AND " + RawContacts.DELETED + "=0", null)
            .withExpectedCount(1)
            .build());
    resolver.applyBatch(ContactsContract.AUTHORITY, ops);
  }
}
