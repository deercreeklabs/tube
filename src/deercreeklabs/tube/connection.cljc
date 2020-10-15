(ns deercreeklabs.tube.connection
  (:refer-clojure :exclude [send])
  (:require
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.tube.utils :as u]
   #?(:clj [primitive-math]))
  #?(:clj
     (:import
      (java.io ByteArrayOutputStream))))

#?(:clj
   (primitive-math/use-primitive-operators))

(def max-num-fragments 2147483647) ;; 2^31-1
(def ping-control-code 16)
(def pong-control-code 17)

(defprotocol IConnection
  (set-on-rcv! [this on-rcv] "Set the receive handler")
  (get-conn-id [this] "Return the connection id")
  (get-uri [this])
  (get-remote-addr [this])
  (get-state [this] "Return the state for this connection")
  (send [this data] "Send binary bytes over this connection")
  (send-ping [this] "Send a tube-specific ping (not an RFC6455 ping)")
  (send-pong [this] "Send a tube-specific pong (not an RFC6455 pong)")
  (close [this] [this code reason ws-already-closed?] "Close this connection")
  (handle-data [this data] "The network layer calls this on receipt of data")
  (handle-connected* [this data] "Internal use only")
  (handle-ready* [this data] "Internal use only")
  (handle-ready-end* [this data compressed?] "Internal use only")
  (handle-msg-in-flight* [this data] "Internal use only")
  (on-disconnect* [this code reason conn-count] "Internal use only"))

(defn send* [conn data compress *peer-fragment-size sender]
  (let [[compression-id compressed] (compress data)
        frags (ba/byte-array->fragments compressed
                                        ;; leave room for header
                                        (- (int @*peer-fragment-size) 6))
        num-frags (count frags)
        _ (when (> num-frags (int max-num-fragments))
            (throw (ex-info "Maximum message fragments exceeded."
                            {:type :illegal-argument
                             :subtype :too-many-fragments
                             :num-fragments num-frags
                             :max-num-framents max-num-fragments})))
        first-byte (bit-shift-left compression-id 3)
        header (if (<= num-frags 7)
                 (ba/byte-array [(bit-or first-byte num-frags)])
                 (ba/concat-byte-arrays
                  [(ba/byte-array [first-byte])
                   (ba/encode-int num-frags)]))
        frags (update frags 0 #(ba/concat-byte-arrays [header %]))]
    (doseq [frag frags]
      (sender frag))))

(deftype Connection
    [conn-id uri remote-addr on-connect conn-req sender closer
     fragment-size compress client? output-stream *on-rcv *on-disconnect *state
     *peer-fragment-size *num-fragments-expected *num-fragments-rcvd
     *cur-msg-compressed? *conn-count]
  IConnection
  (set-on-rcv! [this on-rcv]
    (reset! *on-rcv on-rcv))

  (get-conn-id [this]
    conn-id)

  (get-uri [this]
    uri)

  (get-remote-addr [this]
    remote-addr)

  (get-state [this]
    @*state)

  (send [this data]
    (case @*state
      :connected (throw (ex-info
                         "Attempt to send before negotiation is complete."
                         {:type :execution-error
                          :subtype :send-before-negotiation-complete
                          :state @*state}))
      :ready (send* this data compress *peer-fragment-size sender)
      :msg-in-flight (send* this data compress *peer-fragment-size sender)
      :shutdown nil))

  (send-ping [this]
    (sender (ba/byte-array [(bit-shift-left ping-control-code 3)])))

  (send-pong [this]
    (sender (ba/byte-array [(bit-shift-left pong-control-code 3)])))

  (close [this]
    (close this 1000 "Explicit close" false))

  (close [this code reason ws-already-closed?]
    (when-not (= :shutdown @*state)
      (reset! *state :shutdown)
      (when-not ws-already-closed?
        (closer))))

  (handle-data [this data]
    (case @*state
      :connected (handle-connected* this data)
      :ready (handle-ready* this data)
      :msg-in-flight (handle-msg-in-flight* this data)
      :shutdown nil))

  (handle-connected* [this data]
    (let [[peer-fragment-size extra-data] (ba/decode-int data)
          state @*state]
      (reset! *peer-fragment-size peer-fragment-size)
      (when-not client?
        (sender (ba/encode-int fragment-size)))
      (reset! *state :ready)
      (when extra-data
        (throw (ex-info "Extra data in negotiation header."
                        {:type :execution-error
                         :subtype :extra-data-in-negotiation-header
                         :data-str (ba/byte-array->debug-str data)
                         :extra-data-str
                         (ba/byte-array->debug-str extra-data)})))
      (when on-connect
        (if client?
          (on-connect this)
          (on-connect this conn-req @*conn-count)))))

  (handle-ready* [this data]
    (let [masked (bit-and (aget #^bytes data 0) 0xf8)
          code (bit-shift-right masked 3)]
      (condp = code
        0 (handle-ready-end* this data false)
        1 (handle-ready-end* this data true)
        ping-control-code (do
                            (send-pong this)
                            (when (> (count data) 1)
                              (handle-data this (ba/slice-byte-array data 1))))
        pong-control-code (when (> (count data) 1)
                            (handle-data this (ba/slice-byte-array data 1)))
        (throw
         (ex-info (str "Got unknown control code: `" code "`.\n"
                       (u/pprint-str (u/sym-map data masked)))
                  (u/sym-map code data masked))))))

  (handle-ready-end* [this data compressed?]
    (reset! *cur-msg-compressed? compressed?)
    (let [num-frags (bit-and (aget #^bytes data 0) 0x07)
          rest-of-bytes (ba/slice-byte-array data 1)
          [num-frags extra-data] (if (pos? num-frags)
                                   [num-frags rest-of-bytes]
                                   (ba/decode-int rest-of-bytes))]
      (reset! *num-fragments-expected num-frags)
      (reset! *state :msg-in-flight)
      (when extra-data
        (handle-data this extra-data))))

  (handle-msg-in-flight* [this data]
    #?(:clj (.write ^ByteArrayOutputStream output-stream data 0 (count data))
       :cljs (swap! output-stream conj data))
    (swap! *num-fragments-rcvd #(inc (int %))) ;; anon fn for prim math
    (when (= @*num-fragments-rcvd @*num-fragments-expected)
      #?(:clj (.flush ^ByteArrayOutputStream output-stream))
      (reset! *state :ready)
      (reset! *num-fragments-rcvd 0)
      (let [whole #?(:clj (.toByteArray ^ByteArrayOutputStream output-stream)
                     :cljs (ba/concat-byte-arrays @output-stream))
            msg (if @*cur-msg-compressed?
                  ;; TODO: Fix compression
                  whole ;;(ba/inflate whole)
                  whole)]
        #?(:clj (.reset ^ByteArrayOutputStream output-stream)
           :cljs (reset! output-stream []))
        (@*on-rcv this msg)))))

(defn connection
  ([conn-id uri remote-addr on-connect conn-req *conn-count
    sender closer fragment-size compression-type client?]
   (connection conn-id uri remote-addr on-connect conn-req *conn-count
               sender closer fragment-size compression-type client? nil))
  ([conn-id uri remote-addr on-connect conn-req *conn-count
    sender closer fragment-size compression-type client? on-rcv]
   (let [on-rcv (or on-rcv (constantly nil))
         *on-rcv (atom on-rcv)
         ;; Ignore compression type for now. Don't compress.
         ;; TODO: Fix compression
         compress #(vector 0 %)
         output-stream #?(:clj (ByteArrayOutputStream.)
                          :cljs (atom []))
         *on-disconnect (atom nil)
         *state (atom :connected)
         *peer-fragment-size (atom nil)
         *num-fragments-expected (atom nil)
         *num-fragments-rcvd (atom 0)
         *cur-msg-compressed? (atom false)]
     (->Connection conn-id uri remote-addr on-connect conn-req sender closer
                   fragment-size compress client? output-stream *on-rcv
                   *on-disconnect *state *peer-fragment-size
                   *num-fragments-expected *num-fragments-rcvd
                   *cur-msg-compressed? *conn-count))))
