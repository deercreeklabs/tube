(ns deercreeklabs.tube.utils
  "Common code and utilities. Parts from https://github.com/farbetter/utils."
  (:refer-clojure :exclude [byte-array send])
  (:require
   [#?(:clj clj-time.format :cljs cljs-time.format) :as f]
   [#?(:clj clj-time.core :cljs cljs-time.core) :as t]
   [#?(:clj clojure.core.async.impl.protocols
       :cljs cljs.core.async.impl.protocols) :as cap]
   [#?(:clj clojure.test :cljs cljs.test) :as test :include-macros true]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.log-utils :as lu :refer [debugs]]
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
      [deercreeklabs.tube.utils :refer [sym-map]])))

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

;;;;;;;;;;;;;;;;;;;; Utility fns ;;;;;;;;;;;;;;;;;;;;

(defn compress-smart [data]
  (if (<= (count data) 30)
    [0 data]
    (let [deflated (ba/deflate data)]
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

(s/defn platform-kw :- s/Keyword
  []
  (cond
    (jvm?) :jvm
    (node?) :node
    (browser?) :browser
    :else :unknown))

(defn configure-logging []
  (timbre/merge-config!
   {:level :debug
    :output-fn lu/short-log-output-fn
    :appenders
    {:println {:ns-blacklist
               ["org.eclipse.jetty.*"]}}}))

(s/defn current-time-ms :- s/Num
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))
