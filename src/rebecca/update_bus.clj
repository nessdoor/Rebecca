(ns rebecca.update-bus
  (:require [clojure.core.async :refer [chan mix admix unmix
                                        mult tap untap
                                        pub sub unsub]]))

(def update-feed (chan))

(def update-intake-mix (mix update-feed))

(defn connect-to-intake [ch] (admix update-intake-mix ch))

(defn disconnect-from-intake [ch] (unmix update-intake-mix ch))

(def update-distribution-mult (mult update-feed))

(defn tap-update-stream [ch] (tap update-distribution-mult ch))

(defn untap-update-stream [ch] (untap update-distribution-mult ch))

(def xf-unbundle-updates
  (comp
   (map #(get % 1))                     ; Discard ack channel
   cat))                                ; Serialize bundles

(def intake-publisher-bypass (chan 3 xf-unbundle-updates))

(tap-update-stream intake-publisher-bypass)

(defmulti topic-selector :type)

(defmethod topic-selector :rebecca/message [_] :rebecca/message)
(defmethod topic-selector :rebecca/account [_] :rebecca/account)
(defmethod topic-selector :rebecca/datasource [_] :rebecca/datasource)

(def update-publisher (pub intake-publisher-bypass topic-selector))

(defn sub-to-event-bus [topic ch] (sub update-publisher topic ch))

(defn unsub-from-event-bus [topic ch] (unsub update-publisher topic ch))
