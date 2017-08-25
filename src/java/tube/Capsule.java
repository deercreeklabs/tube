/**
 * Autogenerated by Avro
 *
 * DO NOT EDIT DIRECTLY
 */
package tube;

import org.apache.avro.specific.SpecificData;
import org.apache.avro.message.BinaryMessageEncoder;
import org.apache.avro.message.BinaryMessageDecoder;
import org.apache.avro.message.SchemaStore;

@SuppressWarnings("all")
@org.apache.avro.specific.AvroGenerated
public class Capsule extends org.apache.avro.specific.SpecificRecordBase implements org.apache.avro.specific.SpecificRecord {
  private static final long serialVersionUID = 8253121607404728445L;
  public static final org.apache.avro.Schema SCHEMA$ = new org.apache.avro.Schema.Parser().parse("{\"type\":\"record\",\"name\":\"Capsule\",\"namespace\":\"tube\",\"fields\":[{\"name\":\"msg_type_id\",\"type\":\"int\"},{\"name\":\"msg_data\",\"type\":[\"null\",\"bytes\"],\"default\":null},{\"name\":\"msg_schema\",\"type\":[\"null\",\"string\"],\"default\":null}]}");
  public static org.apache.avro.Schema getClassSchema() { return SCHEMA$; }

  private static SpecificData MODEL$ = new SpecificData();

  private static final BinaryMessageEncoder<Capsule> ENCODER =
      new BinaryMessageEncoder<Capsule>(MODEL$, SCHEMA$);

  private static final BinaryMessageDecoder<Capsule> DECODER =
      new BinaryMessageDecoder<Capsule>(MODEL$, SCHEMA$);

  /**
   * Return the BinaryMessageDecoder instance used by this class.
   */
  public static BinaryMessageDecoder<Capsule> getDecoder() {
    return DECODER;
  }

  /**
   * Create a new BinaryMessageDecoder instance for this class that uses the specified {@link SchemaStore}.
   * @param resolver a {@link SchemaStore} used to find schemas by fingerprint
   */
  public static BinaryMessageDecoder<Capsule> createDecoder(SchemaStore resolver) {
    return new BinaryMessageDecoder<Capsule>(MODEL$, SCHEMA$, resolver);
  }

  /** Serializes this Capsule to a ByteBuffer. */
  public java.nio.ByteBuffer toByteBuffer() throws java.io.IOException {
    return ENCODER.encode(this);
  }

  /** Deserializes a Capsule from a ByteBuffer. */
  public static Capsule fromByteBuffer(
      java.nio.ByteBuffer b) throws java.io.IOException {
    return DECODER.decode(b);
  }

  @Deprecated public int msg_type_id;
  @Deprecated public java.nio.ByteBuffer msg_data;
  @Deprecated public java.lang.CharSequence msg_schema;

  /**
   * Default constructor.  Note that this does not initialize fields
   * to their default values from the schema.  If that is desired then
   * one should use <code>newBuilder()</code>.
   */
  public Capsule() {}

  /**
   * All-args constructor.
   * @param msg_type_id The new value for msg_type_id
   * @param msg_data The new value for msg_data
   * @param msg_schema The new value for msg_schema
   */
  public Capsule(java.lang.Integer msg_type_id, java.nio.ByteBuffer msg_data, java.lang.CharSequence msg_schema) {
    this.msg_type_id = msg_type_id;
    this.msg_data = msg_data;
    this.msg_schema = msg_schema;
  }

  public org.apache.avro.Schema getSchema() { return SCHEMA$; }
  // Used by DatumWriter.  Applications should not call.
  public java.lang.Object get(int field$) {
    switch (field$) {
    case 0: return msg_type_id;
    case 1: return msg_data;
    case 2: return msg_schema;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }

  // Used by DatumReader.  Applications should not call.
  @SuppressWarnings(value="unchecked")
  public void put(int field$, java.lang.Object value$) {
    switch (field$) {
    case 0: msg_type_id = (java.lang.Integer)value$; break;
    case 1: msg_data = (java.nio.ByteBuffer)value$; break;
    case 2: msg_schema = (java.lang.CharSequence)value$; break;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }

  /**
   * Gets the value of the 'msg_type_id' field.
   * @return The value of the 'msg_type_id' field.
   */
  public java.lang.Integer getMsgTypeId() {
    return msg_type_id;
  }

  /**
   * Sets the value of the 'msg_type_id' field.
   * @param value the value to set.
   */
  public void setMsgTypeId(java.lang.Integer value) {
    this.msg_type_id = value;
  }

  /**
   * Gets the value of the 'msg_data' field.
   * @return The value of the 'msg_data' field.
   */
  public java.nio.ByteBuffer getMsgData() {
    return msg_data;
  }

  /**
   * Sets the value of the 'msg_data' field.
   * @param value the value to set.
   */
  public void setMsgData(java.nio.ByteBuffer value) {
    this.msg_data = value;
  }

  /**
   * Gets the value of the 'msg_schema' field.
   * @return The value of the 'msg_schema' field.
   */
  public java.lang.CharSequence getMsgSchema() {
    return msg_schema;
  }

  /**
   * Sets the value of the 'msg_schema' field.
   * @param value the value to set.
   */
  public void setMsgSchema(java.lang.CharSequence value) {
    this.msg_schema = value;
  }

  /**
   * Creates a new Capsule RecordBuilder.
   * @return A new Capsule RecordBuilder
   */
  public static tube.Capsule.Builder newBuilder() {
    return new tube.Capsule.Builder();
  }

  /**
   * Creates a new Capsule RecordBuilder by copying an existing Builder.
   * @param other The existing builder to copy.
   * @return A new Capsule RecordBuilder
   */
  public static tube.Capsule.Builder newBuilder(tube.Capsule.Builder other) {
    return new tube.Capsule.Builder(other);
  }

  /**
   * Creates a new Capsule RecordBuilder by copying an existing Capsule instance.
   * @param other The existing instance to copy.
   * @return A new Capsule RecordBuilder
   */
  public static tube.Capsule.Builder newBuilder(tube.Capsule other) {
    return new tube.Capsule.Builder(other);
  }

  /**
   * RecordBuilder for Capsule instances.
   */
  public static class Builder extends org.apache.avro.specific.SpecificRecordBuilderBase<Capsule>
    implements org.apache.avro.data.RecordBuilder<Capsule> {

    private int msg_type_id;
    private java.nio.ByteBuffer msg_data;
    private java.lang.CharSequence msg_schema;

    /** Creates a new Builder */
    private Builder() {
      super(SCHEMA$);
    }

    /**
     * Creates a Builder by copying an existing Builder.
     * @param other The existing Builder to copy.
     */
    private Builder(tube.Capsule.Builder other) {
      super(other);
      if (isValidValue(fields()[0], other.msg_type_id)) {
        this.msg_type_id = data().deepCopy(fields()[0].schema(), other.msg_type_id);
        fieldSetFlags()[0] = true;
      }
      if (isValidValue(fields()[1], other.msg_data)) {
        this.msg_data = data().deepCopy(fields()[1].schema(), other.msg_data);
        fieldSetFlags()[1] = true;
      }
      if (isValidValue(fields()[2], other.msg_schema)) {
        this.msg_schema = data().deepCopy(fields()[2].schema(), other.msg_schema);
        fieldSetFlags()[2] = true;
      }
    }

    /**
     * Creates a Builder by copying an existing Capsule instance
     * @param other The existing instance to copy.
     */
    private Builder(tube.Capsule other) {
            super(SCHEMA$);
      if (isValidValue(fields()[0], other.msg_type_id)) {
        this.msg_type_id = data().deepCopy(fields()[0].schema(), other.msg_type_id);
        fieldSetFlags()[0] = true;
      }
      if (isValidValue(fields()[1], other.msg_data)) {
        this.msg_data = data().deepCopy(fields()[1].schema(), other.msg_data);
        fieldSetFlags()[1] = true;
      }
      if (isValidValue(fields()[2], other.msg_schema)) {
        this.msg_schema = data().deepCopy(fields()[2].schema(), other.msg_schema);
        fieldSetFlags()[2] = true;
      }
    }

    /**
      * Gets the value of the 'msg_type_id' field.
      * @return The value.
      */
    public java.lang.Integer getMsgTypeId() {
      return msg_type_id;
    }

    /**
      * Sets the value of the 'msg_type_id' field.
      * @param value The value of 'msg_type_id'.
      * @return This builder.
      */
    public tube.Capsule.Builder setMsgTypeId(int value) {
      validate(fields()[0], value);
      this.msg_type_id = value;
      fieldSetFlags()[0] = true;
      return this;
    }

    /**
      * Checks whether the 'msg_type_id' field has been set.
      * @return True if the 'msg_type_id' field has been set, false otherwise.
      */
    public boolean hasMsgTypeId() {
      return fieldSetFlags()[0];
    }


    /**
      * Clears the value of the 'msg_type_id' field.
      * @return This builder.
      */
    public tube.Capsule.Builder clearMsgTypeId() {
      fieldSetFlags()[0] = false;
      return this;
    }

    /**
      * Gets the value of the 'msg_data' field.
      * @return The value.
      */
    public java.nio.ByteBuffer getMsgData() {
      return msg_data;
    }

    /**
      * Sets the value of the 'msg_data' field.
      * @param value The value of 'msg_data'.
      * @return This builder.
      */
    public tube.Capsule.Builder setMsgData(java.nio.ByteBuffer value) {
      validate(fields()[1], value);
      this.msg_data = value;
      fieldSetFlags()[1] = true;
      return this;
    }

    /**
      * Checks whether the 'msg_data' field has been set.
      * @return True if the 'msg_data' field has been set, false otherwise.
      */
    public boolean hasMsgData() {
      return fieldSetFlags()[1];
    }


    /**
      * Clears the value of the 'msg_data' field.
      * @return This builder.
      */
    public tube.Capsule.Builder clearMsgData() {
      msg_data = null;
      fieldSetFlags()[1] = false;
      return this;
    }

    /**
      * Gets the value of the 'msg_schema' field.
      * @return The value.
      */
    public java.lang.CharSequence getMsgSchema() {
      return msg_schema;
    }

    /**
      * Sets the value of the 'msg_schema' field.
      * @param value The value of 'msg_schema'.
      * @return This builder.
      */
    public tube.Capsule.Builder setMsgSchema(java.lang.CharSequence value) {
      validate(fields()[2], value);
      this.msg_schema = value;
      fieldSetFlags()[2] = true;
      return this;
    }

    /**
      * Checks whether the 'msg_schema' field has been set.
      * @return True if the 'msg_schema' field has been set, false otherwise.
      */
    public boolean hasMsgSchema() {
      return fieldSetFlags()[2];
    }


    /**
      * Clears the value of the 'msg_schema' field.
      * @return This builder.
      */
    public tube.Capsule.Builder clearMsgSchema() {
      msg_schema = null;
      fieldSetFlags()[2] = false;
      return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Capsule build() {
      try {
        Capsule record = new Capsule();
        record.msg_type_id = fieldSetFlags()[0] ? this.msg_type_id : (java.lang.Integer) defaultValue(fields()[0]);
        record.msg_data = fieldSetFlags()[1] ? this.msg_data : (java.nio.ByteBuffer) defaultValue(fields()[1]);
        record.msg_schema = fieldSetFlags()[2] ? this.msg_schema : (java.lang.CharSequence) defaultValue(fields()[2]);
        return record;
      } catch (java.lang.Exception e) {
        throw new org.apache.avro.AvroRuntimeException(e);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static final org.apache.avro.io.DatumWriter<Capsule>
    WRITER$ = (org.apache.avro.io.DatumWriter<Capsule>)MODEL$.createDatumWriter(SCHEMA$);

  @Override public void writeExternal(java.io.ObjectOutput out)
    throws java.io.IOException {
    WRITER$.write(this, SpecificData.getEncoder(out));
  }

  @SuppressWarnings("unchecked")
  private static final org.apache.avro.io.DatumReader<Capsule>
    READER$ = (org.apache.avro.io.DatumReader<Capsule>)MODEL$.createDatumReader(SCHEMA$);

  @Override public void readExternal(java.io.ObjectInput in)
    throws java.io.IOException {
    READER$.read(this, SpecificData.getDecoder(in));
  }

}
