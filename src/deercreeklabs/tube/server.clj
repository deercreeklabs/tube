(ns deercreeklabs.tube.server
  (:require
   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]]))

(def StopperFn (s/=> s/Any))

(defn make-http-handler
  [status headers body]
  (sym-map))

(defn make-ws-handler [on-connect]
  (fn ws-handler [req]
    (try
      (http/with-channel req channel
        (let [error-chan (ca/chan 10)
              sender (fn [data]
                       (when-not (http/send! channel data)
                         (ca/put! error-chan :channel-closed)))
              closer #(http/close channel)
              ch-str (.toString channel)
              [local remote-addr] (clojure.string/split ch-str #"<->")
              handler (on-connect remote-addr sender closer)
              {:keys [on-rcv on-disconnect on-error]} handler]
          (http/on-close channel on-disconnect)
          (http/on-receive channel on-rcv)
          (ca/take! error-chan on-error)
          (debugf "Got connection on %s from %s" (:uri req) remote-addr)))
      (catch Exception e
        (u/log-exception e)))))

(s/defn serve :- StopperFn
  [port :- s/Num
   routes :- {s/Str Handler}]
  (let [handler (bidi.ring/make-handler routes)
        _ (infof (str "Starting server on port " port "."))
        options {:port port
                 :thread (.availableProcessors (Runtime/getRuntime))}
        stopper (org.httpkit.server/run-server options)]
    stopper))
