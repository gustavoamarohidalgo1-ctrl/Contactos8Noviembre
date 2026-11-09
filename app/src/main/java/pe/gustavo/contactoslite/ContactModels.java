package pe.gustavo.contactoslite;

import java.util.List;

public final class ContactModels {
  private ContactModels() {}

  public static final class Summary {
    public final long id;
    public final String lookupKey, name, phone;

    public Summary(long id, String lookupKey, String name, String phone) {
      this.id = id;
      this.lookupKey = lookupKey;
      this.name = name;
      this.phone = phone;
    }
  }

  public static final class Phone {
    public final long id;
    public final String number;
    public final int type;
    public final String label;
    public final boolean readOnly;

    public Phone(long id, String number, int type, String label) {
      this(id, number, type, label, false);
    }

    public Phone(long id, String number, int type, String label, boolean readOnly) {
      this.id = id;
      this.number = number;
      this.type = type;
      this.label = label;
      this.readOnly = readOnly;
    }
  }

  public static final class Source {
    public final long rawId;
    public final String accountName, accountType;
    public final boolean readOnly;
    public final String name;
    public final List<Phone> phones;
    public final long version;

    public Source(
        long rawId,
        String accountName,
        String accountType,
        boolean readOnly,
        String name,
        List<Phone> phones) {
      this(rawId, accountName, accountType, readOnly, name, phones, -1);
    }

    public Source(
        long rawId,
        String accountName,
        String accountType,
        boolean readOnly,
        String name,
        List<Phone> phones,
        long version) {
      this.rawId = rawId;
      this.accountName = accountName;
      this.accountType = accountType;
      this.readOnly = readOnly;
      this.name = name;
      this.phones = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(phones));
      this.version = version;
    }
  }

  public static final class Detail {
    public final long id;
    public final String lookupKey, name;
    public final List<Source> sources;

    public Detail(long id, String lookupKey, String name, List<Source> sources) {
      this.id = id;
      this.lookupKey = lookupKey;
      this.name = name;
      this.sources = sources;
    }
  }

  public static final class AccountChoice {
    public final String name, type, label;

    public AccountChoice(String name, String type, String label) {
      this.name = name;
      this.type = type;
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }
}
