(ns seed.accounts.transfer
  (:require [automat.core :as a]
           [seed.core.process :as process]
           [seed.core.command :as command]
           [seed.core.aggregate :as aggregate]
           [seed.accounts.account :as account]
           [seed.core.util :refer [success]]))

(defrecord InitiateTransfer [id from to amount])
(defrecord CompleteTransfer [])
(defrecord FailTransfer [])

(defrecord TransferInitiated [id from to amount])
(defrecord TransferCompleted [])
(defrecord TransferFailed [])

(def pattern
  [(a/or [:transfer-initiated
          :command-failed (a/$ :fail-transfer)
          :transfer-failed]

         [:transfer-initiated
          :account-credited
          :command-failed (a/$ :reverse-credit)
          :account-debited (a/$ :fail-transfer)
          :transfer-failed]

         [:transfer-initiated (a/$ :credit-from-account)
          :account-credited (a/$ :debit-to-account)
          :account-debited (a/$ :complete-transfer)
          :transfer-completed])])

(defn- credit-from-account [{{{:keys [from amount]} :data} :trigger-event :as state} input]
  (->>
    {:number from :amount amount :currency "EUR" ::command/stream-id from}
    account/map->CreditAccount
    (assoc state :command)))

(defn- debit-to-account [{{{:keys [to amount]} :data} :trigger-event :as state} input]
  (->>
    {:number to :amount amount :currency "EUR" ::command/stream-id to}
    account/map->DebitAccount
    (assoc state :command)))

(defn- reverse-credit [{{{:keys [from amount]} :data} :trigger-event
                        {{:keys [cause]} :data} :event :as state} input]
  (->>
    {:number from :amount amount :currency "EUR" ::command/stream-id from :cause cause}
    account/map->DebitAccount
    (assoc state :command)))

(defn- complete-transfer [{{{:keys [id]} :data} :trigger-event :as state} input]
  (->>
    {:process-id id ::command/stream-id id}
    map->CompleteTransfer
    (assoc state :command)))

(defn- fail-transfer [{{{:keys [cause]} :data :as event} :event
                       {{:keys [id]} :data} :trigger-event
                       :as state} input]
  (->>
    {:process-id id ::command/stream-id id :cause cause}
    map->FailTransfer
    (assoc state :command)))

(def reducers
  {:credit-from-account credit-from-account
   :debit-to-account debit-to-account
   :complete-transfer complete-transfer
   :reverse-credit reverse-credit
   :fail-transfer fail-transfer})

(extend-protocol aggregate/Aggregate
  TransferInitiated
  (state [event state]
    (assoc event
         :state :initiated))

  TransferCompleted
  (state [event state]
    (assoc state
           :state :completed))

  TransferFailed
  (state [event state]
    (assoc state
           :state :failed
           :cause (:cause event))))

(extend-protocol command/CommandHandler
  InitiateTransfer
  (perform [command state]
   (success [(map->TransferInitiated command)]))

  CompleteTransfer
  (perform [command state]
    (success [(map->TransferCompleted command)]))

  FailTransfer
  (perform [command state]
    (success [(map->TransferFailed command)])))

