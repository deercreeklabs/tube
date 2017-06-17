(ns deercreeklabs.tube.utils
  "Common code and utilities. Parts from https://github.com/farbetter/utils."
  (:refer-clojure :exclude [byte-array send])
  (:require
   [#?(:clj clojure.core.async :cljs cljs.core.async) :as async]
   [#?(:clj clojure.core.async.impl.protocols
       :cljs cljs.core.async.impl.protocols) :as cap]
   #?(:clj [clojure.test :as test :refer [is]] :cljs [cljs.test :as test])
   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  #?(:clj
     (:import
      (com.google.common.primitives Bytes)
      (java.io ByteArrayInputStream ByteArrayOutputStream)
      (java.util Arrays)
      (java.util.zip DeflaterOutputStream InflaterOutputStream))))

;;;;;;;;;;;;;;;;;;;; Macro-writing utils ;;;;;;;;;;;;;;;;;;;;

;; From: http://blog.nberger.com.ar/blog/2015/09/18/more-portable-complex-macro-musing/
(defn- cljs-env?
  "Take the &env from a macro, and return whether we are expanding into cljs."
  [env]
  (boolean (:ns env)))

(defmacro if-cljs
  "Return `then` if we are generating cljs code and `else` for Clojure code.
  https://groups.google.com/d/msg/clojurescript/iBY5HaQda4A/w1lAQi9_AwsJ"
  [then else]
  (if (cljs-env? &env) then else))

;;;;;;;;;;;;;;;;;;;; Macros ;;;;;;;;;;;;;;;;;;;;

(defmacro sym-map
  "Builds a map from symbols.
   Symbol names are turned into keywords and become the map's keys.
   Symbol values become the map's values.
  (let [a 1
        b 2]
    (sym-map a b))  =>  {:a 1 :b 2}"
  [& syms]
  (zipmap (map keyword syms) syms))

;;;;;;;;;;;;;;;;;;;; Protocols and Records ;;;;;;;;;;;;;;;;;;;;

(defprotocol IWebSocket
  (send [this data] "Sends data (a byte array) over the websocket")
  (disconnect [this] "Disconnects the websocket"))

(defrecord WebSocket [peer-addr sender closer]
  IWebSocket
  (send [this data]
    (sender data))
  (disconnect [this]
    (closer)))

(defn make-websocket [peer-addr sender closer]
  (->WebSocket peer-addr sender closer))

;;;;;;;;;;;;;;;;;;;; Schemas ;;;;;;;;;;;;;;;;;;;;

(def Nil (s/eq nil))

(def ByteArray
  #?(:clj
     (class (clojure.core/byte-array []))
     :cljs
     js/Int8Array))

(def SizeOrSeq
  (s/if integer?
    s/Num
    [s/Any]))

(def WebSocketOptions
  {(s/optional-key :on-connect) (s/=> WebSocket)
   (s/optional-key :on-disconnect) (s/=> WebSocket s/Str)
   (s/optional-key :on-rcv) (s/=> WebSocket ByteArray)
   (s/optional-key :on-error) (s/=> WebSocket s/Str)
   (s/optional-key :compression-type-kw) (s/maybe (s/enum :deflate :none))})

(def Channel (s/protocol cap/Channel))

;;;;;;;;;;;;;;;;;;;; byte-arrays ;;;;;;;;;;;;;;;;;;;;

(s/defn byte-array? :- s/Bool
  [x :- s/Any]
  (when-not (nil? x)
    (boolean (= ByteArray (class x)))))

(s/defn concat-byte-arrays :- (s/maybe ByteArray)
  [arrays :- (s/maybe [ByteArray])]
  (when arrays
    (case (count arrays)
      0 nil
      1 (first arrays)
      #?(:clj (Bytes/concat (into-array arrays))
         :cljs
         (let [lengths (map count arrays)
               new-array (js/Int8Array. (apply + lengths))
               offsets (loop [lens lengths
                              pos 0
                              positions [0]]
                         (if (= 1 (count lens))
                           positions
                           (let [[len & rest] lens
                                 new-pos (+ pos len)]
                             (recur rest
                                    new-pos
                                    (conj positions new-pos)))))]
           (dotimes [i (count arrays)]
             (let [v (nth arrays i)
                   offset (nth offsets i)]
               (.set new-array v offset)))
           new-array)))))

#?(:cljs
   (s/defn byte-array-cljs :- ByteArray
     ([size-or-seq :- SizeOrSeq]
      (if (sequential? size-or-seq)
        (byte-array-cljs (count size-or-seq) size-or-seq)
        (byte-array-cljs size-or-seq 0)))
     ([size init-val-or-seq]
      (let [ba (js/Int8Array. size)]
        (if (sequential? init-val-or-seq)
          (.set ba (clj->js init-val-or-seq))
          (.fill ba init-val-or-seq))
        ba))))

(s/defn byte-array :- ByteArray
  ([size-or-seq :- SizeOrSeq]
   (#?(:clj clojure.core/byte-array
       :cljs byte-array-cljs) size-or-seq))
  ([size :- SizeOrSeq
    init-val-or-seq :- (s/if sequential?
                         [s/Any]
                         s/Any)]
   (#?(:clj clojure.core/byte-array
       :cljs byte-array-cljs) size init-val-or-seq)))

(s/defn equivalent-byte-arrays? :- s/Bool
  [a :- ByteArray
   b :- ByteArray]
  (let [cmp (fn [acc i]
              (and acc
                   (= (aget #^bytes a i)
                      (aget #^bytes b i))))
        result (and (= (count a) (count b))
                    (reduce cmp true (range (count a))))]
    result))

;; Make cljs byte-arrays countable
#?(:cljs
   (extend-protocol ICounted
     js/Int8Array
     (-count [this]
       (if this
         (.-length this)
         0))))

#?(:cljs
   (extend-protocol ICounted
     js/Uint8Array
     (-count [this]
       (if this
         (.-length this)
         0))))

(s/defn slice-byte-array :- ByteArray
  "Return a slice of the given byte array.
   Args:
        - array - Array to be sliced. Required.
        - start - Start index. Optional. Defaults to 0.
        - end - End index. Optional. If not provided, the slice will extend
             to the end of the array. The returned slice will not contain
             the byte at the end index position, i.e.: the slice fn uses
             a half-open interval."
  ([array :- ByteArray]
   (slice-byte-array array 0))
  ([array :- ByteArray
    start :- s/Num]
   (slice-byte-array array start (count array)))
  ([array :- ByteArray
    start :- s/Num
    end :- s/Num]
   (when (> start end)
     (throw (ex-info "Slice start is greater than end."
                     {:type :illegal-argument
                      :subtype :slice-start-is-greater-than-end
                      :start start
                      :end end})))
   (let [stop (min end (count array))]
     #?(:clj
        (Arrays/copyOfRange ^bytes array ^int start ^int stop)
        :cljs
        (.slice array start stop)))))

(s/defn reverse-byte-array :- ByteArray
  "Returns a new byte array with bytes reversed."
  [ba :- ByteArray]
  (-> (reverse ba)
     (byte-array)))

(s/defn byte-array->chunks :- [ByteArray]
  [ba :- ByteArray
   chunk-size :- s/Int]
  (debugf "### (count ba):%s chunk-size: %s" (count ba) chunk-size)
  (if (zero? chunk-size)
    (slice-byte-array ba)
    (loop [offset 0
           output []]
      (if (>= offset (count ba))
        output
        (let [end-offset (+ offset chunk-size)
              chunk (slice-byte-array ba offset end-offset)]
          (recur end-offset
                 (conj output chunk)))))))

(s/defn byte-array->debug-str :- s/Str
  [ba :- ByteArray]
  #?(:clj (str "[" (clojure.string/join "," (map str ba)) "]")
     :cljs (str ba)))

(s/defn read-zig-zag-encoded-int :- [(s/one s/Int :int)
                                     (s/optional ByteArray :unread-remainder)]
  "Takes an zig-zag encoded byte array and reads an integer from it.
   Returns a vector of the integer and, optionally, any unread bytes."
  [ba :- ByteArray]
  (loop [n 0
         i 0
         out 0]
    (let [b (aget ba n)]
      (if (zero? (bit-and b 0x80))
        (let [zz-n (-> (bit-shift-left b i)
                       (bit-or out))
              int-out (->> (bit-and zz-n 1)
                            (- 0)
                            (bit-xor (unsigned-bit-shift-right zz-n 1)))]
          (if (< (inc n) (count ba))
            [int-out (slice-byte-array ba (inc n))]
            [int-out]))
        (let [out (-> (bit-and b 0x7f)
                      (bit-shift-left i)
                      (bit-or out))
              i (+ 7 i)]
          (if (<= i 31)
            (recur (inc n) i out)
            (throw
             (ex-info "Variable-length quantity is more than 32 bits"
                      {:type :illegal-argument
                       :subtype :var-len-num-more-than-32-bits
                       :i i}))))))))

(s/defn int->zig-zag-encoded-byte-array :- ByteArray
  "Zig zag encodes an integer. Returns the encoded bytes."
  [i :- s/Int]
  (let [zz-n (bit-xor (bit-shift-left i 1) (bit-shift-right i 31))]
    (loop [n zz-n
           out []]
      (if (zero? (bit-and n -128))
        (byte-array (conj out (bit-and n 0x7f)))
        (let [b (-> (bit-and n 0x7f)
                    (bit-or 0x80))]
          (recur (unsigned-bit-shift-right n 7)
                 (conj out b)))))))

#?(:cljs
   (defn signed-byte-array->unsigned-byte-array [b]
     (js/Uint8Array. b)))

#?(:cljs
   (defn unsigned-byte-array->signed-byte-array [b]
     (js/Int8Array. b)))

;;;;;;;;;;;;;;;;;;;; Compression / Decompression ;;;;;;;;;;;;;;;;;;;;

#?(:cljs
   (def pako (or (this-as this (.-pako this))
                 js/module.exports)))

(s/defn deflate :- (s/maybe ByteArray)
  [data :- (s/maybe ByteArray)]
  (when data
    #?(:clj
       (let [os (ByteArrayOutputStream.)
             ds (DeflaterOutputStream. os)]
         (.write ^DeflaterOutputStream ds ^bytes data)
         (.close ds)
         (.toByteArray os))
       :cljs
       (->> data
            (signed-byte-array->unsigned-byte-array)
            (.deflate pako)
            (unsigned-byte-array->signed-byte-array)))))

(s/defn inflate :- (s/maybe ByteArray)
  [deflated-data :- (s/maybe ByteArray)]
  (when deflated-data
    #?(:clj
       (let [os (ByteArrayOutputStream.)
             infs (InflaterOutputStream. os)]
         (.write ^InflaterOutputStream infs ^bytes deflated-data)
         (.close infs)
         (.toByteArray os))
       :cljs
       (->> deflated-data
            (signed-byte-array->unsigned-byte-array)
            (.inflate pako)
            (unsigned-byte-array->signed-byte-array)))))

(defn compression-type-kw->info [compression-type-kw]
  (let [[compress decompress compression-type-id]
        (case compression-type-kw
          :deflate [deflate inflate 1]
          :none [identity identity 0]
          nil [identity identity 0]
          (throw (ex-info
                  (str "Illegal compression-type-kw: " compression-type-kw)
                  {:type :illegal-argument
                   :subtype :illegal-compression-option
                   :compression-type-kw compression-type-kw})))]
    (sym-map compress decompress compression-type-id compression-type-kw)))

(defn compression-type-id->info [compression-type-id]
  (let [[compress decompress compression-type-kw]
        (case compression-type-id
          1 [deflate inflate :deflate]
          0 [identity identity :none]
          nil [identity identity :none]
          (throw (ex-info (str "Illegal compression-type-id: "
                               compression-type-id)
                          {:type :illegal-argument
                           :subtype :illegal-compression-option
                           :compression-type-id compression-type-id})))]
    (sym-map compress decompress compression-type-id compression-type-kw)))

;;;;;;;;;;;;;;;;;;;; Exceptions ;;;;;;;;;;;;;;;;;;;;

(s/defn get-exception-msg :- s/Str
  [e]
  #?(:clj (.toString ^Exception e)
     :cljs (.-message e)))

(s/defn get-exception-stacktrace :- s/Str
  [e]
  #?(:clj (clojure.string/join "\n" (map str (.getStackTrace ^Exception e)))
     :cljs (.-stack e)))

(s/defn get-exception-msg-and-stacktrace :- s/Str
  [e]
  (str "\nException:\n"
       (get-exception-msg e)
       "\nStacktrace:\n"
       (get-exception-stacktrace e)))

(defn log-exception [e]
  (errorf (get-exception-msg-and-stacktrace e)))

;;;;;;;;;;;;;;;;;;;; core.async utils ;;;;;;;;;;;;;;;;;;;;

(defmacro go-sf-helper [ex-type body]
  `(try
     [:success (do ~@body)]
     (catch ~ex-type e#
       [:failure e#])))

(defmacro go-sf [& body]
  `(if-cljs
    (cljs.core.async.macros/go
      (go-sf-helper :default ~body))
    (clojure.core.async/go
      (go-sf-helper Exception ~body))))

(defn call-sf-helper [status ret f]
  (if (= :success status)
    ret
    (if (instance? #?(:cljs js/Error
                      :clj Throwable) ret)
      (throw ret)
      (throw (ex-info "call-sf! call failed"
                      {:type :execution-error
                       :subtype :async-call-failed
                       :f f
                       :reason ret})))))

(defmacro call-sf! [f & args]
  `(if-cljs
    (let [[status# ret#] (cljs.core.async/<! (~f ~@args))]
      (call-sf-helper status# ret# ~f))
    (let [[status# ret#] (clojure.core.async/<! (~f ~@args))]
      (call-sf-helper status# ret# ~f))))

#?(:clj
   (defmacro call-sf!! [f & args]
     `(let [[status# ret#] (clojure.core.async/<!! (~f ~@args))]
        (call-sf-helper status# ret# ~f))))

;;;;;;;;;;;;;;;;;;;; Async test helpers ;;;;;;;;;;;;;;;;;;;;

(defn- check-status [status ret]
  (when (not= :success status)
    (if (instance? #?(:cljs js/Error
                      :clj Throwable) ret)
      (throw ret)
      (throw (ex-info "Asnyc test failed with an error."
                      {:type :test-failure
                       :subtype :error-in-async-test
                       :status status
                       :ret ret})))))

(s/defn test-async* :- s/Any
  [timeout-ms :- s/Num
   go-sf-ch :- Channel]
  (go-sf
   (let [t (async/timeout timeout-ms)
         [ret ch] (async/alts! [go-sf-ch t])
         [status result] ret]
     (if (= t ch)
       (is (not= t ch)
           (str "Test should have finished within " timeout-ms "ms."))
       (check-status status result)))))

(s/defn test-async :- s/Any
  ([go-sf-ch :- Channel]
   (test-async 1000 go-sf-ch))
  ([timeout-ms :- s/Num
    go-sf-ch :- Channel]
   (let [ch (test-async* timeout-ms go-sf-ch)]
     #?(:clj
        (let [[status ret] (async/<!! ch)]
          (check-status status ret))
        :cljs
        (async done
               (async/take! ch (fn [ret]
                              (let [[status result] ret]
                                (check-status status result ))
                              (done))))))))
