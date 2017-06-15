(ns deercreeklabs.tube.utils
  "Common code and utilities. Parts from https://github.com/farbetter/utils."
  (:refer-clojure :exclude [send])
  (:require
   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  #?(:clj
     (:import
      (com.google.common.primitives Bytes)
      (java.io ByteArrayInputStream ByteArrayOutputStream)
      (java.util.zip DeflaterOutputStream InflaterOutputStream))))

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
  (disconnect [this] "Disconnects the websocket")
  (get-peer-addr [this] "Returns a string representing the peer's address"))

(defrecord WebSocket [peer-addr sender closer]
  IWebSocket
  (send [this data]
    (sender data))
  (disconnect [this]
    (closer))
  (get-peer-addr [this]
    peer-addr))

(defn make-websocket [peer-addr sender closer]
  (->WebSocket peer-addr sender closer))

;;;;;;;;;;;;;;;;;;;; Schemas ;;;;;;;;;;;;;;;;;;;;

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
  {(s/optional-key :on-connect) (s/=> (s/protocol IWebSocket))
   (s/optional-key :on-disconnect) (s/=> (s/protocol IWebSocket) s/Str)
   (s/optional-key :on-rcv) (s/=> (s/protocol IWebSocket) ByteArray)
   (s/optional-key :on-error) (s/=> (s/protocol IWebSocket) s/Str)})

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
#?(:cljs (extend-protocol ICounted
           js/Int8Array
           (-count [this]
             (if this
               (.-length this)
               0))))

#?(:cljs (extend-protocol ICounted
           js/Uint8Array
           (-count [this]
             (if this
               (.-length this)
               0))))
#_
(s/defn slice :- ByteArray
  "Return a slice of the given byte array.
   Args:
        - array - Array to be sliced. Required.
        - start - Start index. Required.
        - end - End index. Optional. If not provided, the slice will extend
             to the end of the array. The returned slice will not contain
             the byte at the end index position, i.e.: the slice fn uses
             a half-open interval."
  ([array :- ByteArray
    start :- s/Num]
   (slice array start (count array)))
  ([array :- ByteArray
    start :- s/Num
    end :- s/Num]
   (when (> start end)
     (throw-far-error "Slice start is greater than end."
                      :illegal-argument :slice-start-is-greater-than-end
                      (sym-map start end)))
   (let [stop (min end (count array))]
     #?(:clj
        (Arrays/copyOfRange ^bytes array ^int start ^int stop)
        :cljs
        (.slice array start stop)))))

#?(:cljs
   (defn signed-byte-array->unsigned-byte-array [b]
     (js/Uint8Array. b)))

#?(:cljs
   (defn unsigned-byte-array->signed-byte-array [b]
     (js/Int8Array. b)))

;;;;;;;;;;;;;;;;;;;; Compression / Decompression ;;;;;;;;;;;;;;;;;;;;

#?(:cljs (def pako (or (this-as this (.-pako this))
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
