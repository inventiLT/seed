(ns seed.accounts.transfer
  (require [automat.core :as a]
           [seed.core.process :as process]
           [seed.core.command :as command]
           [seed.accounts.account :as account]
           [seed.core.util :refer [success]]))

(defrecord InitiateTransfer [id from to amount])
(defrecord CompleteTransfer [])
(defrecord FailTransfer [])

(defrecord TransferInitiated [id from to amount])
(defrecord TransferCompleted [])
(defrecord TransferFailed [])

(def transfer-pattern
  [(a/or [:transfer-initiated
          :command-failed (a/$ :fail-transfer)
          :transfer-failed]

         [:transfer-initiated
          :account-credited
          :command-failed (a/$ :reverse-credit)
          :account-debited (a/$ :fail-transfer)]

         [:transfer-initiated (a/$ :credit-from-account)
          :account-credited (a/$ :debit-to-account)
          :account-debited (a/$ :complete-transfer)
          :transfer-completed])])

(defn- credit-from-account [{{{:keys [from amount]} :data} :trigger-event :as state} input]
  (->>
    {:account-number from :amount amount :currency "EUR" :stream-id from}
    account/map->CreditAccount
    (assoc state :command)))

(defn- debit-to-account [{{{:keys [to amount]} :data} :trigger-event :as state} input]
  (->>
    {:account-number to :amount amount :currency "EUR" :stream-id to}
    account/map->DebitAccount
    (assoc state :command)))

(defn- complete-transfer [{{{:keys [process-id]} :metadata} :trigger-event :as state} input]
  (->>
    {:process-id process-id :stream-id process-id}
    map->CompleteTransfer
    (assoc state :command)))

(defn- fail-transfer [{{{:keys [process-id]} :metadata
                        {:keys [cause]} :data :as event} :event
                       :as state} input]
  (->>
    {:process-id process-id :stream-id process-id :cause cause}
    map->FailTransfer
    (assoc state :command)))

(def transfer-process
  (a/compile
    [transfer-pattern]
    {:reducers
     {:credit-from-account credit-from-account
      :debit-to-account debit-to-account
      :complete-transfer complete-transfer
      :fail-transfer fail-transfer}}))

(defprotocol TransferProcess
  (state [event state]))

(extend-protocol TransferProcess
  TransferInitiated
  (state [event state]
    (assoc state
         :state :initiated))

  TransferCompleted
  (state [event state]
    (assoc state
           :state :completed))

  TransferFailed
  (state [event state]
    (assoc state
           :state :failed)))

(extend-protocol command/CommandHandler
  InitiateTransfer
  (perform [command state]
   (success [(apply ->TransferInitiated (vals command))]))

  CompleteTransfer
  (perform [command state]
    (success [(map->TransferCompleted command)]))

  FailTransfer
  (perform [command state]
    (success [(map->TransferFailed command)])))

