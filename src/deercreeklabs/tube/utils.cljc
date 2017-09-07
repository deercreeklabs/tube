(ns deercreeklabs.tube.utils
  "Common code and utilities. Parts from https://github.com/farbetter/utils."
  (:refer-clojure :exclude [byte-array send])
  (:require
   #?(:cljs [cljsjs.pako])
   [#?(:clj clj-time.format :cljs cljs-time.format) :as f]
   [#?(:clj clj-time.core :cljs cljs-time.core) :as t]
   [clojure.core.async :as ca]
   [#?(:clj clojure.core.async.impl.protocols
       :cljs cljs.core.async.impl.protocols) :as cap]
   [#?(:clj clojure.test :cljs cljs.test) :as test :include-macros true]
   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  #?(:clj
     (:import
      (com.google.common.primitives Bytes)
      (java.io ByteArrayInputStream ByteArrayOutputStream)
      (java.util Arrays)
      (java.util.zip DeflaterOutputStream InflaterOutputStream))
     :cljs
     (:require-macros
      [cljs.core.async.macros :as ca]
      deercreeklabs.tube.utils)))

#?(:cljs
   (set! *warn-on-infer* true))

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

(def Channel (s/protocol cap/Channel))

;;;;;;;;;;;;;;;;;;;; byte-arrays ;;;;;;;;;;;;;;;;;;;;

#?(:cljs (def class type))

(s/defn byte-array? :- s/Bool
  [x :- s/Any]
  (when-not (nil? x)
    (boolean (= ByteArray (class x)))))

(s/defn concat-byte-arrays :- (s/maybe ByteArray)
  [arrays :- (s/maybe [(s/maybe ByteArray)])]
  (when arrays
    (let [arrays (keep identity arrays)]
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
             new-array))))))

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
  (and
   (= (count a) (count b))
   (let [num (count a)]
     (loop [i 0]
       (if (>= i num)
         true
         (if (= (aget ^bytes a i)
                (aget ^bytes b i))
           (recur (int (inc i)))
           false))))))

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

(s/defn byte-array->debug-str :- s/Str
  [ba :- ByteArray]
  #?(:clj (str "[" (clojure.string/join "," (map str ba)) "]")
     :cljs (str ba)))

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
   (slice-byte-array array 0 (count array)))
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
  (let [num (count ba)
        last (dec num)
        new (byte-array num)]
    (dotimes [i num]
      (aset ^bytes new i ^byte (aget ^bytes ba (- last i))))
    new))

#?(:clj
   (s/defn read-byte-array-from-file :- ByteArray
     [filename :- s/Str]
     (let [file ^java.io.File (clojure.java.io/file filename)
           result (byte-array (.length file))]
       (with-open [in (java.io.DataInputStream.
                       (clojure.java.io/input-stream file))]
         (.readFully in result))
       result)))

#?(:clj
   (s/defn write-byte-array-to-file :- Nil
     [filename :- s/Str
      ba :- ByteArray]
     (with-open [out (clojure.java.io/output-stream
                      (clojure.java.io/file filename))]
       (.write out ^bytes ba))
     nil))

(s/defn byte-array->fragments :- [ByteArray]
  [ba :- ByteArray
   fragment-size :- s/Int]
  (if (zero? fragment-size)
    (slice-byte-array ba)
    (loop [offset 0
           output []]
      (if (>= offset (count ba))
        output
        (let [end-offset (+ offset fragment-size)
              fragment (slice-byte-array ba offset end-offset)]
          (recur (int end-offset)
                 (conj output fragment)))))))

(s/defn decode-int :- [(s/one s/Int :int)
                       (s/optional ByteArray :unread-remainder)]
  "Takes an zig-zag encoded byte array and reads an integer from it.
   Returns a vector of the integer and, optionally, any unread bytes."
  [ba :- ByteArray]
  (loop [n 0
         i 0
         out 0]
    (let [b (aget ^bytes ba n)]
      (if (zero? (bit-and b 0x80))
        (let [zz-n (-> (bit-shift-left b i)
                       (bit-or out))
              int-out (->> (bit-and zz-n 1)
                           (- 0)
                           (bit-xor (unsigned-bit-shift-right zz-n 1)))]
          (if (< (inc n) (count ba))
            (do
              [int-out (slice-byte-array ba (inc n))])
            [int-out]))
        (let [out (-> (bit-and b 0x7f)
                      (bit-shift-left i)
                      (bit-or out))
              i (+ 7 i)]
          (if (<= i 31)
            (recur (inc n) (int i) (int out))
            (throw
             (ex-info "Variable-length quantity is more than 32 bits"
                      {:type :illegal-argument
                       :subtype :var-len-num-more-than-32-bits
                       :i i}))))))))

(s/defn encode-int :- ByteArray
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

(defn compress-smart [data]
  (if (<= (count data) 15)
    [0 data]
    (let [deflated (deflate data)]
      (if (<= (count data) (count deflated))
        [0 data]
        [1 deflated]))))

;;;;;;;;;;;;;;;;;;;; Platform detection ;;;;;;;;;;;;;;

(s/defn jvm? :- s/Bool
  []
  #?(:clj true
     :cljs false))

(s/defn browser? :- s/Bool
  []
  #?(:clj false
     :cljs
     (exists? js/navigator)))

(s/defn node? :- s/Bool
  []
  #?(:clj false
     :cljs (boolean (= "nodejs" cljs.core/*target*))))

(s/defn jsc-ios? :- s/Bool
  []
  #?(:clj false
     :cljs
     (try
       (boolean (= "jsc-ios" js/JSEnv))
       (catch :default e
         false))))

;; TODO: Return which browser (e.g. chrome, safari, etc.)
(s/defn get-platform-kw :- s/Keyword
  []
  (cond
    (jvm?) :jvm
    (node?) :node
    (jsc-ios?) :jsc-ios
    (browser?) :browser
    :else :unknown))

;;;;;;;;;;;;;;;;;;;; Logging & Exceptions ;;;;;;;;;;;;;;;;;;;;

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

(s/defn short-log-output-fn :- s/Str
  [data :- {(s/required-key :level) s/Keyword
            s/Any s/Any}]
  (let [{:keys [level msg_ ?ns-str ?file ?line]} data
        formatter (f/formatters  :hour-minute-second-ms)
        timestamp (f/unparse formatter (t/now))]
    (str
     timestamp " "
     (clojure.string/upper-case (name level))  " "
     "[" (or ?ns-str ?file "?") ":" (or ?line "?") "] - "
     @msg_)))

(defn configure-logging []
  (timbre/merge-config!
   {:level :debug
    :output-fn short-log-output-fn
    :appenders
    {:println {:ns-blacklist
               ["org.eclipse.jetty.*"]}}}))

;;;;;;;;;;;;;;;;;;;; core.async utils ;;;;;;;;;;;;;;;;;;;;

(defn check-ret [ret]
  (if (instance? #?(:cljs js/Error :clj Throwable) ret)
    (throw ret)
    ret))

(defmacro <? [ch-expr]
  `(check-ret (clojure.core.async/<! ~ch-expr)))

(defmacro alts? [chs-expr]
  `(let [[v# ch#] (clojure.core.async/alts! ~chs-expr)]
     [(check-ret v#) ch#]))

;;;;;;;;;;;;;;;;;;;; Async test helpers ;;;;;;;;;;;;;;;;;;;;

(s/defn test-async* :- s/Any
  [timeout-ms :- s/Num
   test-ch :- Channel]
  (ca/go
    (let [[ret ch] (ca/alts! [test-ch (ca/timeout timeout-ms)])]
      (if (= test-ch ch)
        ret
        (throw (ex-info (str "Async test did not complete within "
                             timeout-ms " ms.")
                        {:type :test-failure
                         :subtype :async-test-timeout
                         :timeout-ms timeout-ms}))))))

(s/defn test-async :- s/Any
  ([test-ch :- Channel]
   (test-async 1000 test-ch))
  ([timeout-ms :- s/Num
    test-ch :- Channel]
   (let [ch (test-async* timeout-ms test-ch)]
     #?(:clj (check-ret (ca/<!! ch))
        :cljs (cljs.test/async
               done (ca/take! ch (fn [ret]
                                   (try
                                     (check-ret ret)
                                     (finally
                                       (done))))))))))
