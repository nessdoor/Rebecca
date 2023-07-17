(ns rebecca.telegram
  (:require [clojure.core.async :refer [>!! <!! chan]]
            [clojure.core.cache.wrapped :as c]
            [clojure.walk :as w]
            [java-time.api :as jt]
            [meander.epsilon :as m]
            [telegrambot-lib.core :as tbot]
            [rebecca.message :as msg]
            [rebecca.update-bus :as bus])
  (:import java.net.URI))

;; TODO: implement proper dispatching based on type of update
(def xf-unwrap-message (map #(update % 1 :message)))

(defn qualify-map
  "Takes a map and a namespace, and returns a map where all (keyword) keys are
  qualified with that namespace. It ignores keys that are already
  qualified."
  [m ns]
  (let [q (fn [k] (if (qualified-keyword? k)
                    k
                    (keyword (str ns) (name k))))]
    (w/postwalk #(if (map? %) (update-keys % q) %) m)))

(def key-namespace "org.telegram")

(def xf-qualify-map (map #(update % 1 (fn [d] (qualify-map d key-namespace)))))

(derive :org.telegram/message :rebecca/message)
(derive :org.telegram/user :rebecca/account)
(derive :org.telegram/chat :rebecca/datasource)

(def authority "telegram.org")

(defn chatid->uri [chat-id]
  (URI. "news" authority (str "/" chat-id "/") nil nil))
(defmethod msg/object-id :org.telegram/chat
  [c] (chatid->uri (:org.telegram/id c)))

(defn msg->uri [chat-id msg-id]
  (.resolve (chatid->uri chat-id) (URI. (str msg-id))))
(defmethod msg/object-id :org.telegram/message
  [m] (msg->uri (chatid->uri (:org.telegram/from m))
                (:org.telegram/message_id m)))

(defn userid->uri [user-id]
  (URI. "acct" (str user-id "@" authority) nil))
(defmethod msg/object-id :org.telegram/user
  [u] (userid->uri (:org.telegram/id u)))

(defn- with-time-meta [o t]
  (vary-meta o assoc :timestamp t))

(def xf-fractionate-normalize
  (map
   #(update % 1
            (fn [d]
              (m/match d {:org.telegram/from {:org.telegram/id ?uid :as ?speaker}
                          :org.telegram/chat {:org.telegram/id ?cid :as ?source}
                          :org.telegram/message_id ?mid
                          :org.telegram/text ?text
                          :org.telegram/date ?date
                          :as ?message}
                (let [u-uri (userid->uri ?uid)
                      c-uri (chatid->uri ?cid)
                      m-uri (msg->uri ?cid ?mid)
                      timestamp (-> ?date
                                    (jt/seconds)
                                    (jt/as :millis)
                                    (jt/instant))]
                  [(-> (assoc ?speaker
                              :type :org.telegram/user
                              :id u-uri)
                       (with-time-meta timestamp))
                   (-> (assoc ?source
                              :type :org.telegram/chat
                              :id c-uri)
                       (with-time-meta timestamp))
                   (-> ?message
                       (dissoc :org.telegram/text :org.telegram/date)
                       (assoc :type :org.telegram/message
                              :id m-uri
                              :speaker u-uri
                              :source c-uri
                              :text ?text
                              :timestamp timestamp
                              :org.telegram/from ?uid
                              :org.telegram/chat ?cid)
                       (with-time-meta timestamp))]))))))

(def xf-update-pipeline
  (comp
   xf-unwrap-message
   xf-qualify-map
   xf-fractionate-normalize))

(def intake-ch (chan 1 xf-update-pipeline clojure.pprint/pprint))

(bus/connect-to-intake intake-ch)

(def known-updates
  "Cache for storing the ID of updates that we have already received. It
  is known that the remote endpoint retries message sends with a
  frequency of around 1 minute, and that the sequence count is reset
  after 1 week."
  (c/ttl-cache-factory {} :ttl (* 5 60 1000))) ; TTL = 5 minutes

(def multiup-ack-ch (chan))

(defn ingest-updates [ups]
  (let [new (vec (remove #(c/lookup known-updates (:update_id %)) ups))
        last (peek new)
        others (butlast new)]
    (if last
      (do
        (doseq [o others]
          (c/miss known-updates (:update_id o) true)
          (>!! intake-ch [nil o]))
        (c/miss known-updates (:update_id last) true)
        (>!! intake-ch [multiup-ack-ch last])
        (<!! multiup-ack-ch)
        (:update_id last))
      -2)))

(def bot (tbot/create))

(def allowed-updates ["message"])

(defn polling-loop
  ([] (polling-loop -1))
  ([last-update]
   (do
     (Thread/sleep 5000)
     (let [{status :ok updates :result err :error :as response}
           (tbot/get-updates bot {:offset last-update
                                  :allowed_updates allowed-updates})]
       (if (or status err)
         (if (= 0 (count updates))
           (recur last-update)
           (recur (+ 1 (ingest-updates updates))))
         (do (prn "Error: " response)
             (recur last-update)))))))
