(ns deercreeklabs.tube.utils
  "Common code and utilities."
  (:require
   [deercreeklabs.baracus :as ba]
   [schema.core :as s])
  #?(:cljs
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

(s/defn ex-msg :- s/Str
  [e]
  #?(:clj (.toString ^Exception e)
     :cljs (.-message e)))

(s/defn ex-stacktrace :- s/Str
  [e]
  #?(:clj (clojure.string/join "\n" (map str (.getStackTrace ^Exception e)))
     :cljs (.-stack e)))

(s/defn ex-msg-and-stacktrace :- s/Str
  [e]
  (str "\nException:\n"
       (ex-msg e)
       "\nStacktrace:\n"
       (ex-stacktrace e)))

(s/defn current-time-ms :- s/Num
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defn noop-logger [level msg]
  )

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
