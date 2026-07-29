#!/usr/bin/env nbb
;; Talk to Filecoin mainnet, for real.
;;
;; Every check here is **read-only**: `ChainHead`, `StateGetActor`,
;; `MpoolGetNonce`, `GasEstimateMessageGas` and `StateCall`. None of them
;; needs a key, none of them costs anything, and none of them puts a byte on
;; chain — `StateCall` executes a message against current state and throws
;; the result away.
;;
;; That is enough to close the sentence every README in this family carries:
;; "no live request has ever been made from this code". The last check is the
;; interesting one — it runs an FEVM contract call built by
;; `filecoin.cloud.evm`, against the real PDPVerifier at its real mainnet
;; address, and decodes the answer. Passing it means the method number, the
;; CBOR params wrapper, the f410f addressing, the function selector and the
;; ABI decoding are all right *together*, which no offline test can show.
;;
;;   npm run live
(ns live
  (:require [filecoin.client :as client]
            [filecoin.cloud.chain :as chain]
            [filecoin.cloud.evm :as evm]
            [filecoin.cloud.pdp :as pdp]
            [filecoin.transport :as transport]))

(def endpoint (:rpc (chain/chain :mainnet)))
(def http (transport/http))

;; An address with a long history, taken from this family's own mainnet
;; vectors. Read-only: we ask the network about it, we do not act as it.
(def observed-account "f12rtnjcyf7h2xobmhtblat7usff7fjqu4crcob5q")

(def results (atom []))

(defn- check [label p]
  (-> (p)
      (.then (fn [v] (swap! results conj [:ok label v]) (println "  ok  " label "→" (pr-str v))))
      (.catch (fn [e] (swap! results conj [:fail label (str e)])
                (println "  FAIL" label "→" (str e))))))

(defn -main []
  (println "endpoint:" endpoint)
  (-> (js/Promise.resolve nil)
      (.then #(check "ChainHead height" (fn [] (client/height http endpoint))))
      (.then #(check "StateNetworkName" (fn [] (client/network-name http endpoint))))
      (.then #(check "MpoolGetNonce" (fn [] (client/nonce http endpoint observed-account))))
      (.then #(check "WalletBalance (attoFIL)"
                     (fn [] (client/balance http endpoint observed-account))))
      (.then #(check "GasEstimateMessageGas"
                     (fn []
                       (-> (client/estimate-gas
                            http endpoint
                            {:to observed-account :from observed-account
                             :value "1" :method 0 :nonce 0})
                           (.then (fn [m] (select-keys m [:gas-limit :gas-fee-cap :gas-premium])))))))
      ;; the one that exercises the whole stack
      (.then #(check "PDPVerifier.getChallengeFinality() via StateCall"
                     (fn []
                       (client/read-contract
                        http endpoint
                        (evm/call-message :mainnet :pdp-verifier
                                          (pdp/call :get-challenge-finality [])
                                          {:from observed-account :nonce 0
                                           :gas-limit 100000000
                                           :gas-fee-cap "0" :gas-premium "0"})
                        ["uint256"]))))
      (.then #(check "PDPVerifier.getNextDataSetId() via StateCall"
                     (fn []
                       (client/read-contract
                        http endpoint
                        (evm/call-message :mainnet :pdp-verifier
                                          (pdp/call :get-next-data-set-id [])
                                          {:from observed-account :nonce 0
                                           :gas-limit 100000000
                                           :gas-fee-cap "0" :gas-premium "0"})
                        ["uint256"]))))
      (.then (fn [_]
               (let [failed (filter #(= :fail (first %)) @results)]
                 (println)
                 (println (count @results) "checks," (count failed) "failed")
                 (when (seq failed) (js/process.exit 1)))))))

(-main)
