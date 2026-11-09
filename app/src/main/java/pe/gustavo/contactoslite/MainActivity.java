package pe.gustavo.contactoslite;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private ContactsRepository repo;
  private LinearLayout root, body, phoneRows;
  private EditText search, nameInput;
  private TextView count;
  private ContactModels.Detail current;
  private ContactModels.Source editing;
  private ContactModels.AccountChoice account;
  private ArrayList<ContactModels.Phone> draftPhones = new ArrayList<>();
  private String draftName = "", query = "", screen = "list";
  private long detailId = -1, editingRawId = -1, draftVersion = -1;
  private String savedAccountName, savedAccountType;
  private boolean savedAccountSelected;
  private int generation = 0;
  private boolean busy,
      visible,
      observerRegistered,
      pendingExternal,
      selectingForInsertOrEdit,
      warningPending;
  private Runnable debounce;
  private Future<?> listFuture;
  private boolean startedOnce;
  private String listPositionQuery;
  private int listPosition, listOffset;
  private Runnable afterWritePermission;
  private static final int READ = 11, WRITE = 12;
  private int bg, surface, ink, muted, accent, border;
  private final ContentObserver observer =
      new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
          if (visible && screen.equals("list")) scheduleSearch();
          else if (visible && !busy && screen.equals("detail") && detailId > 0) {
            if (debounce != null) main.removeCallbacks(debounce);
            debounce =
                () -> {
                  if (visible && !busy && screen.equals("detail") && detailId > 0)
                    openDetail(detailId);
                };
            main.postDelayed(debounce, 250);
          }
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    bg = Color.BLACK;
    surface = Color.parseColor("#1C1C1C");
    ink = Color.parseColor("#F5F5F5");
    muted = Color.parseColor("#B5B5B5");
    accent = Color.parseColor("#F5F5F5");
    border = Color.parseColor("#4A4A4A");
    getWindow().setStatusBarColor(bg);
    getWindow().setNavigationBarColor(bg);
    repo = new ContactsRepository(this);
    if (state != null) {
      screen = state.getString("screen", "list");
      selectingForInsertOrEdit = state.getBoolean("selectingForInsertOrEdit");
      warningPending = state.getBoolean("warningPending");
      detailId = state.getLong("detailId", -1);
      editingRawId = state.getLong("rawId", -1);
      draftVersion = state.getLong("version", -1);
      savedAccountSelected = state.getBoolean("accountSelected");
      savedAccountName = state.getString("accountName");
      savedAccountType = state.getString("accountType");
      draftName = state.getString("draftName", "");
      query = state.getString("query", "");
      listPositionQuery = state.getString("listPositionQuery");
      listPosition = state.getInt("listPosition");
      listOffset = state.getInt("listOffset");
      ArrayList<String> nums = state.getStringArrayList("numbers");
      long[] ids = state.getLongArray("phoneIds");
      int[] types = state.getIntArray("phoneTypes");
      ArrayList<String> labels = state.getStringArrayList("phoneLabels");
      if (nums != null)
        for (int i = 0; i < nums.size(); i++)
          draftPhones.add(new ContactModels.Phone(ids[i], nums.get(i), types[i], labels.get(i)));
    }
    pendingExternal =
        (state == null || screen.equals("loading"))
            && getIntent() != null
            && getIntent().getAction() != null
            && !Intent.ACTION_MAIN.equals(getIntent().getAction());
    if (hasRead()) startContent();
    else showReadPermission();
  }

  @Override
  protected void onStart() {
    super.onStart();
    visible = true;
    if (hasRead()) {
      registerObserver();
      if (screen.equals("permission")) {
        screen = "list";
        startContent();
      } else if (startedOnce && screen.equals("list")) loadList();
      else if (startedOnce && !busy && screen.equals("detail") && detailId > 0)
        openDetail(detailId);
    }
    startedOnce = true;
  }

  @Override
  protected void onStop() {
    visible = false;
    if (observerRegistered) {
      getContentResolver().unregisterContentObserver(observer);
      observerRegistered = false;
    }
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    generation++;
    main.removeCallbacksAndMessages(null);
    worker.shutdown();
    super.onDestroy();
  }

  private void postUi(Runnable action) {
    main.post(
        () -> {
          if (!isDestroyed() && !isFinishing()) action.run();
        });
  }

  private void registerObserver() {
    if (!observerRegistered)
      try {
        getContentResolver()
            .registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer);
        observerRegistered = true;
      } catch (SecurityException e) {
        permissionLost();
      }
  }

  private boolean hasRead() {
    return checkSelfPermission(Manifest.permission.READ_CONTACTS)
        == PackageManager.PERMISSION_GRANTED;
  }

  private boolean hasWrite() {
    return checkSelfPermission(Manifest.permission.WRITE_CONTACTS)
        == PackageManager.PERMISSION_GRANTED;
  }

  private void startContent() {
    registerObserver();
    if (pendingExternal || warningPending) {
      pendingExternal = false;
      warningPending = false;
      handleIntent(getIntent());
    } else if (screen.equals("edit")) restoreEditor();
    else if (!busy && screen.equals("detail") && detailId > 0) openDetail(detailId);
    else showList();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    generation++;
    afterWritePermission = null;
    setIntent(intent);
    pendingExternal = true;
    if (hasRead()) {
      pendingExternal = false;
      handleIntent(intent);
    } else showReadPermission();
  }

  private void handleIntent(Intent intent) {
    String action = intent.getAction();
    if ((Intent.ACTION_INSERT.equals(action)
            || Intent.ACTION_EDIT.equals(action)
            || Intent.ACTION_INSERT_OR_EDIT.equals(action))
        && hasUnsupportedExtras(intent)) {
      warningPending = true;
      new AlertDialog.Builder(this)
          .setMessage(
              "Esta versión solo puede añadir nombre y teléfonos. Los otros datos recibidos no se"
                  + " añadirán.")
          .setNegativeButton("Cancelar", (dialog, which) -> finish())
          .setPositiveButton(
              "Continuar con nombre y teléfonos",
              (dialog, which) -> {
                warningPending = false;
                handleSupportedIntent(intent);
              })
          .setOnCancelListener(dialog -> finish())
          .show();
      return;
    }
    handleSupportedIntent(intent);
  }

  private boolean hasUnsupportedExtras(Intent intent) {
    Bundle extras = intent.getExtras();
    if (extras == null) return false;
    for (String key : extras.keySet()) {
      if (!ContactsContract.Intents.Insert.NAME.equals(key)
          && !ContactsContract.Intents.Insert.PHONE.equals(key)
          && !("tracing_intent_id".equals(key) && extras.get(key) instanceof Long)) return true;
    }
    return false;
  }

  private void handleSupportedIntent(Intent intent) {
    String action = intent.getAction();
    selectingForInsertOrEdit = Intent.ACTION_INSERT_OR_EDIT.equals(action);
    if (Intent.ACTION_VIEW.equals(action) && isContactListIntent(intent)) {
      showList();
      return;
    }
    if (selectingForInsertOrEdit) {
      showList();
      return;
    }
    if (Intent.ACTION_INSERT.equals(action)) {
      showList();
      requestWrite(() -> beginCreate());
      return;
    }
    if ((Intent.ACTION_VIEW.equals(action)
            || Intent.ACTION_EDIT.equals(action)
            || ContactsContract.QuickContact.ACTION_QUICK_CONTACT.equals(action))
        && intent.getData() != null) {
      final boolean edit = Intent.ACTION_EDIT.equals(action);
      final Uri uri = intent.getData();
      if (!"content".equals(uri.getScheme())
          || !ContactsContract.AUTHORITY.equals(uri.getAuthority())
          || !isIndividualContactUri(uri)) {
        errorPage("Esta dirección de contacto no es compatible.");
        return;
      }
      screen = "loading";
      int ticket = ++generation;
      showLoading("Abriendo contacto…");
      worker.execute(
          () -> {
            try {
              long id = resolveContact(uri);
              postUi(
                  () -> {
                    if (ticket != generation || !screen.equals("loading")) return;
                    if (id > 0) openDetail(id, edit);
                    else errorPage("No se encontró el contacto.");
                  });
            } catch (Exception e) {
              postUi(
                  () -> {
                    if (ticket == generation && screen.equals("loading")) handleError(e);
                  });
            }
          });
    } else if (ContactsContract.QuickContact.ACTION_QUICK_CONTACT.equals(action))
      errorPage("Esta dirección de contacto no es compatible.");
    else showList();
  }

  private boolean isContactListIntent(Intent intent) {
    Uri uri = intent.getData();
    if (uri == null) {
      String type = intent.getType();
      return ContactsContract.Contacts.CONTENT_TYPE.equals(type)
          || "vnd.android.cursor.dir/person".equals(type);
    }
    if (!"content".equals(uri.getScheme()) || uri.getQuery() != null || uri.getFragment() != null)
      return false;
    java.util.List<String> path = uri.getPathSegments();
    return path.size() == 1
        && ((ContactsContract.AUTHORITY.equals(uri.getAuthority())
                && "contacts".equals(path.get(0)))
            || ("contacts".equals(uri.getAuthority()) && "people".equals(path.get(0))));
  }

  private boolean isIndividualContactUri(Uri uri) {
    java.util.List<String> path = uri.getPathSegments();
    if (uri.getFragment() != null) return false;
    if (uri.getQuery() != null
        && (!uri.getQueryParameterNames().equals(java.util.Collections.singleton("directory"))
            || uri.getQueryParameters("directory").size() != 1
            || !"0".equals(uri.getQueryParameter("directory")))) return false;
    if (path.size() == 2
        && ("contacts".equals(path.get(0))
            || "raw_contacts".equals(path.get(0))
            || "data".equals(path.get(0)))) return isContactId(path.get(1));
    return (path.size() == 3 || path.size() == 4)
        && "contacts".equals(path.get(0))
        && "lookup".equals(path.get(1))
        && !path.get(2).isEmpty()
        && (path.size() == 3 || isContactId(path.get(3)));
  }

  private boolean isContactId(String value) {
    try {
      return Long.parseLong(value) > 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private long resolveContact(Uri uri) {
    Uri contactUri = uri;
    String path = uri.toString();
    if (path.contains("/contacts/lookup/")) {
      Uri found = ContactsContract.Contacts.lookupContact(getContentResolver(), uri);
      if (found != null) contactUri = found;
    }
    String column = ContactsContract.Contacts._ID;
    if (path.contains("/raw_contacts/")) column = ContactsContract.RawContacts.CONTACT_ID;
    else if (path.contains("/data/")) column = ContactsContract.Data.CONTACT_ID;
    try (Cursor c =
        getContentResolver().query(contactUri, new String[] {column}, null, null, null)) {
      if (c != null && c.moveToFirst()) return c.getLong(0);
    }
    return -1;
  }

  private void showReadPermission() {
    screen = "permission";
    page();
    title("Tus contactos, a mano", "Permite el acceso para ver y buscar tu agenda.");
    button(
        body,
        "Permitir acceso",
        true,
        () -> requestPermissions(new String[] {Manifest.permission.READ_CONTACTS}, READ));
    button(body, "Abrir ajustes", false, this::settings);
  }

  private void requestWrite(Runnable next) {
    if (hasWrite()) {
      next.run();
      return;
    }
    afterWritePermission = next;
    new AlertDialog.Builder(this)
        .setTitle("Permiso para modificar contactos")
        .setMessage("Permite modificar contactos para guardar o eliminar los cambios en tu agenda.")
        .setNegativeButton("Cancelar", (d, w) -> afterWritePermission = null)
        .setOnCancelListener(d -> afterWritePermission = null)
        .setPositiveButton(
            "Continuar",
            (d, w) -> requestPermissions(new String[] {Manifest.permission.WRITE_CONTACTS}, WRITE))
        .show();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    boolean ok = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
    if (request == READ) {
      if (ok) {
        screen = "list";
        startContent();
      } else showReadPermission();
    } else if (request == WRITE) {
      Runnable next = afterWritePermission;
      afterWritePermission = null;
      if (ok) {
        if (next != null) next.run();
      } else if (next != null)
        new AlertDialog.Builder(this)
            .setTitle("No se otorgó el permiso")
            .setMessage(
                "Para guardar o eliminar contactos, activa el permiso de contactos en Ajustes.")
            .setNegativeButton("Ahora no", null)
            .setPositiveButton("Ajustes", (d, w) -> settings())
            .show();
    }
  }

  private void settings() {
    startActivity(
        new Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", getPackageName(), null)));
  }

  private void permissionLost() {
    if (!isFinishing()) {
      Toast.makeText(this, "Se necesita acceso a los contactos.", Toast.LENGTH_LONG).show();
      showReadPermission();
    }
  }

  private int dp(float n) {
    return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
  }

  private GradientDrawable shape(int color, int radius, int stroke) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    if (stroke != 0) d.setStroke(dp(1), stroke);
    return d;
  }

  private TextView text(String s, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return t;
  }

  private LinearLayout vertical() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private void page() {
    if (listFuture != null) listFuture.cancel(false);
    if (debounce != null) main.removeCallbacks(debounce);
    afterWritePermission = null;
    saveListPosition();
    search = null;
    count = null;
    nameInput = null;
    phoneRows = null;
    root = vertical();
    root.setBackgroundColor(bg);
    root.setPadding(dp(20), dp(12), dp(20), 0);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          v.setPadding(
              dp(20),
              dp(12) + insets.getSystemWindowInsetTop(),
              dp(20),
              insets.getSystemWindowInsetBottom());
          return insets;
        });
    setContentView(root);
    body = root;
  }

  private void title(String heading, String subtitle) {
    TextView h = text(heading, 30, ink, true);
    body.addView(h);
    if (subtitle != null) {
      TextView s = text(subtitle, 15, muted, false);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
      p.topMargin = dp(5);
      body.addView(s, p);
    }
    space(body, 20);
  }

  private void space(LinearLayout parent, int height) {
    parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
  }

  private Button button(LinearLayout parent, String label, boolean primary, Runnable action) {
    Button b = new Button(this);
    b.setAllCaps(false);
    b.setText(label);
    b.setTextSize(15);
    b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    b.setTextColor(ink);
    b.setBackground(shape(primary ? Color.parseColor("#303030") : surface, 14, border));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
    p.bottomMargin = dp(10);
    parent.addView(b, p);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private TextView clickable(String label, Runnable action) {
    TextView t = text(label, 15, accent, true);
    t.setGravity(Gravity.CENTER_VERTICAL);
    t.setMinHeight(dp(48));
    t.setOnClickListener(v -> action.run());
    return t;
  }

  private void backHeader(String label, Runnable action) {
    body.addView(clickable("‹  " + label, action));
    space(body, 8);
  }

  private ScrollView scroll(LinearLayout parent) {
    ScrollView s = new ScrollView(this);
    s.setFillViewport(false);
    parent.addView(s, new LinearLayout.LayoutParams(-1, 0, 1));
    return s;
  }

  private EditText field(String hint, String value) {
    EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setTextSize(16);
    e.setTextColor(ink);
    e.setHintTextColor(muted);
    e.setHint(hint);
    e.setPadding(dp(16), 0, dp(16), 0);
    e.setMinHeight(dp(52));
    e.setBackground(shape(surface, 14, border));
    if (value != null) e.setText(value);
    return e;
  }

  private void saveListPosition() {
    if (root == null) return;
    ListView list = root.findViewWithTag("list");
    if (list != null && list.getAdapter() != null && listPositionQuery != null) {
      listPosition = list.getFirstVisiblePosition();
      View first = list.getChildAt(0);
      listOffset = first == null ? 0 : first.getTop();
    }
  }

  private void showList() {
    generation++;
    screen = "list";
    current = null;
    page();
    title(
        "Contactos",
        selectingForInsertOrEdit ? "Elige un contacto o crea uno nuevo." : "Tu agenda, ligera.");
    search = field("Buscar por nombre o teléfono", query);
    search.setCompoundDrawablePadding(dp(8));
    body.addView(search, new LinearLayout.LayoutParams(-1, dp(52)));
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

          public void onTextChanged(CharSequence s, int st, int before, int c) {
            query = s.toString();
            scheduleSearch();
          }

          public void afterTextChanged(Editable e) {}
        });
    space(body, 14);
    button(body, "+  Nuevo contacto", true, () -> requestWrite(this::beginCreate));
    count = text("Cargando…", 13, muted, true);
    count.setPadding(dp(2), dp(8), 0, dp(10));
    body.addView(count);
    ListView list = new ListView(this);
    list.setTag("list");
    list.setDivider(new ColorDrawable(Color.TRANSPARENT));
    list.setDividerHeight(dp(8));
    list.setBackgroundColor(bg);
    body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    loadList();
  }

  private void scheduleSearch() {
    generation++;
    if (listFuture != null) listFuture.cancel(false);
    if (debounce != null) main.removeCallbacks(debounce);
    debounce = this::loadList;
    main.postDelayed(debounce, 250);
  }

  private void loadList() {
    if (!screen.equals("list") || !hasRead()) return;
    final int ticket = ++generation;
    final String q = query;
    if (listFuture != null) listFuture.cancel(false);
    listFuture =
        worker.submit(
            () -> {
              try {
                List<ContactModels.Summary> items = repo.list(q);
                postUi(
                    () -> {
                      if (ticket != generation || !screen.equals("list") || isFinishing()) return;
                      renderList(items);
                    });
              } catch (Exception e) {
                postUi(
                    () -> {
                      if (ticket == generation && screen.equals("list")) handleError(e);
                    });
              }
            });
  }

  private void renderList(List<ContactModels.Summary> items) {
    if (root == null) return;
    ListView list = root.findViewWithTag("list");
    if (list == null) return;
    saveListPosition();
    if (!query.equals(listPositionQuery)) {
      listPosition = 0;
      listOffset = 0;
      listPositionQuery = query;
    }
    count.setText(
        getResources().getQuantityString(R.plurals.contact_count, items.size(), items.size()));
    list.setAdapter(
        new android.widget.BaseAdapter() {
          public int getCount() {
            return items.size();
          }

          public Object getItem(int i) {
            return items.get(i);
          }

          public long getItemId(int i) {
            return items.get(i).id;
          }

          public View getView(int i, View recycled, android.view.ViewGroup parent) {
            LinearLayout row;
            if (recycled == null) {
              row = new LinearLayout(MainActivity.this);
              row.setGravity(Gravity.CENTER_VERTICAL);
              row.setPadding(dp(14), dp(10), dp(12), dp(10));
              row.setMinimumHeight(dp(76));
              row.setBackground(shape(surface, 14, 0));
              TextView avatar = text("", 17, ink, true);
              avatar.setGravity(Gravity.CENTER);
              avatar.setBackground(shape(Color.parseColor("#383838"), 50, 0));
              row.addView(avatar, new LinearLayout.LayoutParams(dp(46), dp(46)));
              LinearLayout labels = vertical();
              labels.setPadding(dp(14), 0, 0, 0);
              labels.addView(text("", 16, ink, true));
              labels.addView(text("", 14, muted, false));
              row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
              row.addView(text("›", 24, muted, false));
            } else row = (LinearLayout) recycled;
            ContactModels.Summary item = items.get(i);
            ((TextView) row.getChildAt(0)).setText(initials(item.name));
            LinearLayout labels = (LinearLayout) row.getChildAt(1);
            ((TextView) labels.getChildAt(0)).setText(displayName(item.name));
            TextView phone = (TextView) labels.getChildAt(1);
            phone.setText(item.phone == null ? "" : item.phone);
            phone.setVisibility(
                item.phone == null || item.phone.isEmpty() ? View.GONE : View.VISIBLE);
            row.setContentDescription(
                displayName(item.name) + (item.phone == null ? "" : ", " + item.phone));
            row.setOnClickListener(
                v -> {
                  if (selectingForInsertOrEdit) requestWrite(() -> openDetail(item.id, true));
                  else openDetail(item.id);
                });
            return row;
          }
        });
    if (!items.isEmpty())
      list.setSelectionFromTop(Math.min(listPosition, items.size() - 1), listOffset);
    if (items.isEmpty())
      count.setText(query.isEmpty() ? R.string.empty_contacts : R.string.empty_search);
  }

  private String displayName(String s) {
    return s == null || s.trim().isEmpty() ? "Sin nombre" : s;
  }

  private String initials(String s) {
    s = displayName(s).trim();
    String[] parts = s.split("\\s+");
    return (parts[0].substring(0, 1)
            + (parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : ""))
        .toUpperCase(Locale.getDefault());
  }

  private void openDetail(long id) {
    openDetail(id, false);
  }

  private void openDetail(long id, boolean editAfter) {
    screen = "detail";
    detailId = id;
    showLoading("Cargando contacto…");
    int ticket = ++generation;
    worker.execute(
        () -> {
          try {
            ContactModels.Detail d = repo.detail(id);
            postUi(
                () -> {
                  if (ticket != generation || !screen.equals("detail") || isFinishing()) return;
                  if (d == null) {
                    errorPage("No se encontró el contacto.");
                    return;
                  }
                  current = d;
                  renderDetail();
                  if (editAfter) requestWrite(this::chooseEditSource);
                });
          } catch (Exception e) {
            postUi(
                () -> {
                  if (ticket == generation && screen.equals("detail")) handleError(e);
                });
          }
        });
  }

  private void renderDetail() {
    screen = "detail";
    page();
    backHeader(isExternalDetail() ? "Volver" : "Contactos", this::leaveDetail);
    title(displayName(current.name), "Información de contacto");
    ScrollView sc = scroll(body);
    LinearLayout content = vertical();
    sc.addView(content);
    boolean any = false;
    for (ContactModels.Source src : current.sources)
      for (ContactModels.Phone p : src.phones) {
        if (p.number == null || p.number.trim().isEmpty()) continue;
        any = true;
        LinearLayout card = vertical();
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(shape(surface, 14, 0));
        card.addView(text("TELÉFONO", 12, muted, true));
        TextView number =
            clickable(
                p.number + "   ↗",
                () -> {
                  try {
                    startActivity(
                        new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", p.number, null)));
                  } catch (Exception e) {
                    Toast.makeText(this, "No se pudo abrir el teléfono.", Toast.LENGTH_LONG).show();
                  }
                });
        card.addView(number);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.bottomMargin = dp(8);
        content.addView(card, cp);
      }
    if (!any) content.addView(text("Sin teléfonos", 15, muted, false));
    space(content, 20);
    button(content, "Editar contacto", true, () -> requestWrite(this::chooseEditSource));
    button(content, "Eliminar contacto", false, () -> requestWrite(this::chooseDeleteSource));
  }

  private void chooseSource(
      String title, java.util.function.Consumer<ContactModels.Source> action) {
    ArrayList<ContactModels.Source> choices = new ArrayList<>();
    for (ContactModels.Source s : current.sources) if (!s.readOnly) choices.add(s);
    if (choices.isEmpty()) {
      new AlertDialog.Builder(this)
          .setMessage("Este contacto no tiene fuentes que se puedan modificar.")
          .setPositiveButton("Entendido", null)
          .show();
      return;
    }
    if (choices.size() == 1) {
      action.accept(choices.get(0));
      return;
    }
    String[] labels = new String[choices.size()];
    for (int i = 0; i < labels.length; i++) {
      ContactModels.Source s = choices.get(i);
      labels[i] =
          (s.accountName == null ? "En el dispositivo" : s.accountName)
              + (s.accountType == null ? "" : " · " + s.accountType);
    }
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setItems(labels, (d, w) -> action.accept(choices.get(w)))
        .setNegativeButton("Cancelar", null)
        .show();
  }

  private void chooseEditSource() {
    chooseSource("Elige la cuenta que deseas editar", this::beginEdit);
  }

  private void chooseDeleteSource() {
    chooseSource(
        "Elige la cuenta que deseas eliminar",
        s ->
            new AlertDialog.Builder(this)
                .setTitle("¿Eliminar este contacto?")
                .setMessage(
                    "Se eliminará de esta cuenta y el cambio se sincronizará. Las otras fuentes"
                        + " vinculadas permanecerán.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton(
                    "Eliminar",
                    (d, w) ->
                        runMutation(
                            () -> {
                              repo.delete(s);
                              return 0L;
                            },
                            () -> {
                              Toast.makeText(this, "Contacto eliminado", Toast.LENGTH_SHORT).show();
                              showList();
                            }))
                .show());
  }

  private void beginCreate() {
    int ticket = ++generation;
    screen = "edit";
    editing = null;
    account = null;
    editingRawId = -1;
    detailId = -1;
    draftName =
        (Intent.ACTION_INSERT.equals(getIntent().getAction()) || selectingForInsertOrEdit)
            ? getIntent().getStringExtra(ContactsContract.Intents.Insert.NAME)
            : "";
    if (draftName == null) draftName = "";
    String initialPhone =
        (Intent.ACTION_INSERT.equals(getIntent().getAction()) || selectingForInsertOrEdit)
            ? getIntent().getStringExtra(ContactsContract.Intents.Insert.PHONE)
            : "";
    if (initialPhone == null) initialPhone = "";
    draftPhones.clear();
    draftPhones.add(
        new ContactModels.Phone(
            0, initialPhone, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, null));
    showLoading("Preparando contacto…");
    worker.execute(
        () -> {
          try {
            List<ContactModels.AccountChoice> accounts = repo.writableAccounts();
            postUi(
                () -> {
                  if (ticket == generation && screen.equals("edit"))
                    chooseAccount(accounts, ticket);
                });
          } catch (Exception e) {
            postUi(
                () -> {
                  if (ticket == generation && screen.equals("edit")) handleError(e);
                });
          }
        });
  }

  private void chooseAccount(List<ContactModels.AccountChoice> accounts, int ticket) {
    if (accounts.isEmpty()) {
      errorPage("No hay cuentas disponibles para guardar contactos.");
      return;
    }
    if (accounts.size() == 1) {
      account = accounts.get(0);
      renderEditor();
      return;
    }
    String[] labels = new String[accounts.size()];
    for (int i = 0; i < labels.length; i++) labels[i] = accounts.get(i).label;
    new AlertDialog.Builder(this)
        .setTitle("Guardar en cuenta")
        .setItems(
            labels,
            (d, w) -> {
              if (ticket != generation || !screen.equals("edit")) return;
              account = accounts.get(w);
              renderEditor();
            })
        .setOnCancelListener(
            d -> {
              if (ticket != generation || !screen.equals("edit")) return;
              if (Intent.ACTION_INSERT.equals(getIntent().getAction())) {
                setResult(RESULT_CANCELED);
                finish();
              } else showList();
            })
        .show();
  }

  private void beginEdit(ContactModels.Source s) {
    generation++;
    editing = s;
    editingRawId = s.rawId;
    draftVersion = s.version;
    draftName = s.name == null ? "" : s.name;
    if (Intent.ACTION_EDIT.equals(getIntent().getAction())) {
      String incomingName = getIntent().getStringExtra(ContactsContract.Intents.Insert.NAME);
      if (incomingName != null && !incomingName.trim().isEmpty()) draftName = incomingName;
    }
    draftPhones = new ArrayList<>(s.phones);
    if (selectingForInsertOrEdit || Intent.ACTION_EDIT.equals(getIntent().getAction())) {
      String incoming = getIntent().getStringExtra(ContactsContract.Intents.Insert.PHONE);
      boolean exists = false;
      if (incoming != null && !incoming.trim().isEmpty()) {
        for (ContactModels.Source source : current.sources) {
          for (ContactModels.Phone phone : source.phones) {
            if (phone.number != null
                && (phone.number.equals(incoming)
                    || android.telephony.PhoneNumberUtils.compare(phone.number, incoming))) {
              exists = true;
              break;
            }
          }
        }
        if (!exists)
          draftPhones.add(
              new ContactModels.Phone(
                  0, incoming, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, null));
      }
    }
    if (draftPhones.isEmpty())
      draftPhones.add(
          new ContactModels.Phone(0, "", ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, null));
    screen = "edit";
    renderEditor();
  }

  private void restoreEditor() {
    int ticket = ++generation;
    if (editingRawId < 0) {
      worker.execute(
          () -> {
            try {
              List<ContactModels.AccountChoice> choices = repo.writableAccounts();
              postUi(
                  () -> {
                    if (ticket != generation || !screen.equals("edit")) return;
                    for (ContactModels.AccountChoice c : choices)
                      if (savedAccountSelected
                          && java.util.Objects.equals(c.name, savedAccountName)
                          && java.util.Objects.equals(c.type, savedAccountType)) {
                        account = c;
                        renderEditor();
                        return;
                      }
                    chooseAccount(choices, ticket);
                  });
            } catch (Exception e) {
              postUi(
                  () -> {
                    if (ticket == generation && screen.equals("edit")) handleError(e);
                  });
            }
          });
    } else {
      final long restoredDetailId = detailId;
      final long restoredRawId = editingRawId;
      worker.execute(
          () -> {
            try {
              ContactModels.Detail d = repo.detail(restoredDetailId);
              postUi(
                  () -> {
                    if (ticket != generation || !screen.equals("edit")) return;
                    current = d;
                    if (d != null)
                      for (ContactModels.Source s : d.sources)
                        if (s.rawId == restoredRawId) {
                          if (draftVersion >= 0 && s.version != draftVersion) {
                            errorPage(
                                "El contacto cambió mientras editabas. Vuelve a abrirlo para cargar"
                                    + " la versión actual.");
                            return;
                          }
                          editing = s;
                          for (int i = 0; i < draftPhones.size(); i++) {
                            ContactModels.Phone draft = draftPhones.get(i);
                            for (ContactModels.Phone original : s.phones)
                              if (original.id == draft.id && original.readOnly) {
                                draftPhones.set(i, original);
                                break;
                              }
                          }
                          renderEditor();
                          return;
                        }
                    errorPage("La fuente del contacto ya no está disponible.");
                  });
            } catch (Exception e) {
              postUi(
                  () -> {
                    if (ticket == generation && screen.equals("edit")) handleError(e);
                  });
            }
          });
    }
  }

  private void renderEditor() {
    screen = "edit";
    page();
    backHeader("Cancelar", this::confirmDiscard);
    title(
        editing == null ? "Nuevo contacto" : "Editar contacto",
        editing == null && account != null
            ? "Guardar en: " + account.label
            : "Los demás datos de la cuenta se conservan.");
    ScrollView sc = scroll(body);
    LinearLayout form = vertical();
    sc.addView(form);
    form.addView(text("Nombre", 14, ink, true));
    space(form, 8);
    nameInput = field("Nombre y apellidos", draftName);
    form.addView(nameInput, new LinearLayout.LayoutParams(-1, dp(52)));
    space(form, 20);
    form.addView(text("Teléfonos", 14, ink, true));
    space(form, 8);
    phoneRows = vertical();
    form.addView(phoneRows);
    renderPhones();
    button(
        form,
        "+  Añadir teléfono",
        false,
        () -> {
          captureDraft();
          draftPhones.add(
              new ContactModels.Phone(
                  0, "", ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, null));
          renderPhones();
        });
    space(form, 10);
    button(form, "Guardar contacto", true, this::save);
  }

  private void captureDraft() {
    if (nameInput != null) draftName = nameInput.getText().toString();
    if (phoneRows != null)
      for (int i = 0; i < phoneRows.getChildCount() && i < draftPhones.size(); i++) {
        LinearLayout row = (LinearLayout) phoneRows.getChildAt(i);
        EditText e = (EditText) row.getChildAt(0);
        ContactModels.Phone old = draftPhones.get(i);
        String value = e.getText().toString();
        if (!java.util.Objects.equals(old.number, value))
          draftPhones.set(
              i, new ContactModels.Phone(old.id, value, old.type, old.label, old.readOnly));
      }
  }

  private void renderPhones() {
    phoneRows.removeAllViews();
    for (int i = 0; i < draftPhones.size(); i++) {
      final int index = i;
      ContactModels.Phone p = draftPhones.get(i);
      LinearLayout row = new LinearLayout(this);
      EditText number = field("Número de teléfono", p.number);
      number.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
      number.setEnabled(!p.readOnly);
      row.addView(number, new LinearLayout.LayoutParams(0, dp(52), 1));
      TextView remove =
          clickable(
              "  ✕  ",
              () -> {
                captureDraft();
                draftPhones.remove(index);
                renderPhones();
              });
      remove.setEnabled(!p.readOnly);
      remove.setAlpha(p.readOnly ? 0.35f : 1f);
      remove.setContentDescription("Quitar teléfono " + (i + 1));
      remove.setGravity(Gravity.CENTER);
      row.addView(remove, new LinearLayout.LayoutParams(dp(48), dp(52)));
      LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
      rp.bottomMargin = dp(10);
      phoneRows.addView(row, rp);
    }
  }

  private void save() {
    if (busy) return;
    captureDraft();
    final String n = draftName.trim();
    ArrayList<ContactModels.Phone> phones = new ArrayList<>();
    for (ContactModels.Phone p : draftPhones)
      if (p.id > 0 || (p.number != null && !p.number.trim().isEmpty()))
        phones.add(
            p.number == null || p.number.equals(p.number.trim())
                ? p
                : new ContactModels.Phone(p.id, p.number.trim(), p.type, p.label, p.readOnly));
    if (n.isEmpty()) {
      Toast.makeText(this, "Añade un nombre para el contacto.", Toast.LENGTH_LONG).show();
      return;
    }
    ContactModels.Source source = editing;
    ContactModels.AccountChoice selected = account;
    if (busy) return;
    busy = true;
    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
    worker.execute(
        () -> {
          try {
            long id;
            if (source == null) id = repo.create(n, phones, selected);
            else {
              repo.update(source, n, phones);
              id = detailId;
            }
            final long result = id;
            postUi(
                () -> {
                  busy = false;
                  setRequestedOrientation(
                      android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                  if (isDestroyed()) return;
                  Toast.makeText(this, "Contacto guardado", Toast.LENGTH_SHORT).show();
                  String action = getIntent().getAction();
                  if (result > 0
                      && (Intent.ACTION_INSERT.equals(action)
                          || Intent.ACTION_EDIT.equals(action)
                          || Intent.ACTION_INSERT_OR_EDIT.equals(action))) {
                    setResult(
                        RESULT_OK,
                        new Intent()
                            .setData(
                                ContentUris.withAppendedId(
                                    ContactsContract.Contacts.CONTENT_URI, result)));
                    finish();
                  } else if (result > 0) openDetail(result);
                  else showList();
                });
          } catch (Exception e) {
            postUi(
                () -> {
                  busy = false;
                  setRequestedOrientation(
                      android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                  if (!isDestroyed()) handleError(e);
                });
          }
        });
  }

  private interface Operation {
    long run() throws Exception;
  }

  private void runMutation(Operation op, Runnable success) {
    if (busy) return;
    busy = true;
    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
    worker.execute(
        () -> {
          try {
            op.run();
            postUi(
                () -> {
                  busy = false;
                  setRequestedOrientation(
                      android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                  if (!isDestroyed()) success.run();
                });
          } catch (Exception e) {
            postUi(
                () -> {
                  busy = false;
                  setRequestedOrientation(
                      android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                  if (!isDestroyed()) handleError(e);
                });
          }
        });
  }

  private void showLoading(String message) {
    page();
    TextView t = text(message, 16, muted, false);
    t.setPadding(0, dp(30), 0, 0);
    body.addView(t);
  }

  private void errorPage(String message) {
    generation++;
    screen = "error";
    page();
    title("No se pudo abrir", message);
    button(body, "Volver a contactos", true, this::showList);
  }

  private void handleError(Exception e) {
    if (e instanceof SecurityException) {
      if (!hasRead()) permissionLost();
      else
        new AlertDialog.Builder(this)
            .setTitle("Permiso necesario")
            .setMessage(
                "No se pudo modificar el contacto. Revisa el permiso de contactos en Ajustes.")
            .setNegativeButton("Cerrar", null)
            .setPositiveButton("Ajustes", (d, w) -> settings())
            .show();
    } else {
      String message =
          (e instanceof IllegalStateException || e instanceof IllegalArgumentException)
                  && e.getMessage() != null
              ? e.getMessage()
              : "No se pudo completar la operación. Inténtalo de nuevo.";
      Toast.makeText(this, message, Toast.LENGTH_LONG).show();
      if (screen.equals("list")) {
        count.setText(R.string.contacts_load_error);
      } else if (root != null && root.getChildCount() == 1) errorPage("Inténtalo de nuevo.");
    }
  }

  private void confirmDiscard() {
    if (busy) return;
    new AlertDialog.Builder(this)
        .setTitle("¿Descartar cambios?")
        .setMessage("Los cambios que no hayas guardado se perderán.")
        .setNegativeButton("Seguir editando", null)
        .setPositiveButton(
            "Descartar",
            (d, w) -> {
              if (Intent.ACTION_INSERT.equals(getIntent().getAction())
                  || Intent.ACTION_EDIT.equals(getIntent().getAction())) {
                setResult(RESULT_CANCELED);
                finish();
              } else if (selectingForInsertOrEdit) showList();
              else if (detailId > 0) openDetail(detailId);
              else showList();
            })
        .show();
  }

  private boolean isExternalDetail() {
    String action = getIntent().getAction();
    return Intent.ACTION_VIEW.equals(action) && !isContactListIntent(getIntent())
        || ContactsContract.QuickContact.ACTION_QUICK_CONTACT.equals(action)
        || Intent.ACTION_EDIT.equals(action);
  }

  private void leaveDetail() {
    if (isExternalDetail()) finish();
    else showList();
  }

  @Override
  public void onBackPressed() {
    if (screen.equals("edit")) confirmDiscard();
    else if (screen.equals("detail") || screen.equals("loading") || screen.equals("error"))
      leaveDetail();
    else if (!screen.equals("list")) showList();
    else super.onBackPressed();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    if (screen.equals("edit")) captureDraft();
    saveListPosition();
    out.putString("listPositionQuery", listPositionQuery);
    out.putInt("listPosition", listPosition);
    out.putInt("listOffset", listOffset);
    out.putString("screen", screen);
    out.putBoolean("selectingForInsertOrEdit", selectingForInsertOrEdit);
    out.putBoolean("warningPending", warningPending);
    out.putLong("detailId", detailId);
    out.putLong("rawId", editingRawId);
    out.putLong("version", draftVersion);
    if (account != null) {
      out.putBoolean("accountSelected", true);
      out.putString("accountName", account.name);
      out.putString("accountType", account.type);
    }
    out.putString("draftName", draftName);
    out.putString("query", query);
    ArrayList<String> nums = new ArrayList<>(), labels = new ArrayList<>();
    long[] ids = new long[draftPhones.size()];
    int[] types = new int[draftPhones.size()];
    for (int i = 0; i < draftPhones.size(); i++) {
      ContactModels.Phone p = draftPhones.get(i);
      nums.add(p.number);
      labels.add(p.label);
      ids[i] = p.id;
      types[i] = p.type;
    }
    out.putStringArrayList("numbers", nums);
    out.putStringArrayList("phoneLabels", labels);
    out.putLongArray("phoneIds", ids);
    out.putIntArray("phoneTypes", types);
    super.onSaveInstanceState(out);
  }
}
