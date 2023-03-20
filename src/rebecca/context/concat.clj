(ns rebecca.context.concat
  (:import java.time.DateTimeException
           java.time.temporal.ChronoUnit))

(defn update-context-meta
  [ctxt-meta segment]
  (let [{segq :segments
         ctok :tokens
         testim :tokens-estimator} ctxt-meta
        seg-text (segment :text)      ; New text contained in the segment
        {seg-tokens :tokens           ; (Estimated) number of tokens in new text
         :or {seg-tokens (testim seg-text)}} (meta segment)]
    (merge ctxt-meta
           ;; Enqueue length of new text (chars and tokens)
           {:segments (conj segq [(count seg-text) seg-tokens])
            ;; Increase overall token count
            :tokens (+ ctok seg-tokens)})))

(defn updated-context-time
  [ctxt-time seg-time]
  (when seg-time        ; Some segments are timeless
    ;; Disallow updates that alter the chronological history
    (if (.isBefore seg-time ctxt-time)
      (throw (new DateTimeException
                  (format "Appended information is older than context (%s < %s)"
                          seg-time ctxt-time)))
      {:timestamp seg-time})))

(defn pop-segments
  [queue char-acc toks-count toks-limit]
  (if (<= toks-count toks-limit)
    [char-acc toks-count queue]         ; If lower limit reached, return
    (let [[ch toks] (peek queue)]       ; Otherwise, pop segment and recur
      (recur (pop queue) (+ char-acc ch) (+ toks-count toks) toks-limit))))

(defn trim-history
  [ctxt]
  (let [cmeta (meta ctxt)
        [cut-chars trimmed-len segq]
        (let [{:keys [segments tokens tokens-limit trim-factor]} cmeta]
          (pop-segments segments 0 tokens (* tokens-limit trim-factor)))]
    (vary-meta
     (assoc ctxt
            ;; Truncate history by cut-chars, leaving the primer intact
            {:text (let [primlen (:primer cmeta) ctext (:text ctxt)]
                     (str (subs ctext 0 primlen)
                          (subs ctext (+ primlen cut-chars))))})
     merge
     {:tokens trimmed-len :segments segq})))

(defn ccat-unsafe
  [ctxt segment]
  ;; Generate a new context with updated metadata
  (vary-meta
   (merge ctxt
          ;; Concatenate new text to context
          {:text (str (ctxt :text) "\n" (segment :text))}
          ;; Update context timestamp
          (updated-context-time (ctxt :timestamp)
                                (segment :timestamp)))
   ;; Generate updated metadata through helper function
   update-context-meta segment))

(defn ccat
  [ctxt segment]
  (let [new-ctxt (ccat-unsafe ctxt segment) ; Unchecked segment concatenation
        {ctok :tokens ctlim :tokens-limit} (meta new-ctxt)]
    ;; If the total number of tokens surpassed the limit, trim history
    (if (> ctok ctlim)
      (trim-history new-ctxt)
      new-ctxt)))
