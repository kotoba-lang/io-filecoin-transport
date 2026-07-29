# io-filecoin-transport

`filecoin.transport` + `filecoin.client` — **the socket `io-filecoin` refuses
to open**, and the order the calls have to go in.

Everything else in this family builds request maps and hands them back. This
repo sends them, and it is the first one that has ever actually talked to a
node.

## Usage

```clojure
(require '[filecoin.transport :as transport]
         '[filecoin.client :as client])

(def http (transport/http))
(def endpoint "https://api.node.glif.io/rpc/v1")

(client/height http endpoint)                 ; => 6233477
(client/balance http endpoint "f1…")          ; => "27318921114299575156"
(client/read-contract http endpoint msg ["uint256"])

(client/send-message-with-key http endpoint priv message)
;; prepare → sign → push, in that order
```

## The sequence is the point

A single request is easy. The order is where a client goes wrong, in ways no
one request reveals:

- **Gas is estimated on a message that already has its nonce.** The estimate
  is computed from the serialised message and the nonce is part of it.
  Estimating first gives a limit for a different message.
- **`GasEstimateMessageGas` returns the whole message, not three numbers.**
  Lotus fills the fields in and hands it back. Taking only `GasLimit` and
  keeping your own fee cap is how a message ends up priced below the base fee
  and sits in the pool.
- **The signature is taken over the message *after* gas.** Sign first and the
  CID moves underneath you — there is a test asserting exactly that.

So `prepare` is one function rather than two calls a caller might reorder,
and `send-message` is prepare-then-sign-then-push.

## Reading a contract without spending anything

`StateCall` executes a message against current state and throws the result
away — no signature, no gas, no funds, nothing on chain. That is how an FEVM
contract is read from the Filecoin side: build the `InvokeContract` message
with `filecoin.cloud.evm`, run it, decode `Return`.

`Return` is unwrapped **twice**: base64, then a CBOR byte string. Handing the
CBOR to the ABI decoder leaves two bytes of header on the front and shifts
every word. And a reverting call still returns HTTP 200 with a non-zero
`ExitCode`, so decoding its empty `Return` would hand back `[]` and read as
success.

## Verification

The offline suite uses a transport that answers from a fixture and records
what it was asked, so the assertions are about the **conversation**: which
methods, in which order, with which fields already filled in.

The live suite (`npm run live`) is the other half, and it is not run in CI.
Against Filecoin mainnet, read-only, with no key and no funds:

```
endpoint: https://api.node.glif.io/rpc/v1
  ok   ChainHead height → 6233477
  ok   StateNetworkName → "mainnet"
  ok   MpoolGetNonce → 22067
  ok   WalletBalance (attoFIL) → "27318921114299575156"
  ok   GasEstimateMessageGas → {:gas-limit 926078, :gas-fee-cap "54773194", :gas-premium "101618"}
  ok   PDPVerifier.getChallengeFinality() via StateCall → ["150"]
  ok   PDPVerifier.getNextDataSetId() via StateCall → ["1413"]

7 checks, 0 failed
```

The last two are the ones worth having. They run a real call against the
deployed PDPVerifier at its real mainnet address, through the FRC-0042 method
number, the CBOR params wrapper, `f410f` addressing, the function selector and
ABI decoding — five repos at once, and no offline test can show that they are
right *together*.

The live suite found a bug on its first run: `filecoin.rpc/message->json`
skipped normalisation when `:to` was present, which is true of exactly the raw
map a caller writes by hand, and handed a string to the address encoder. Lotus
answers that with `unmarshaling params: unknown address protocol`, which reads
as a malformed request rather than an un-normalised one. Fixed in io-filecoin
`244db55`.

**20 assertions offline, green on both runtimes; 7 live checks against
mainnet.**

```sh
clojure -M:test        # JVM, offline
npm run test:cljs      # nbb, offline
npm run live           # mainnet, read-only
```

### A note on the ClojureScript suite

`IHttp` returns a map on the JVM and a Promise here, so assertions written
inside a `.then` run *after* `run-tests` has reported. Written the obvious
way, this suite was green on both runtimes while the ClojureScript side
asserted 10 where the JVM asserted 19. Every async test now goes through
`clojure.test/async`, and the two counts match.

## What is not here

- **BLS12-381**, so `f3` accounts cannot send. Unchanged across this family.
- **The delegated (FEVM sender) path.** `send-message` signs type 1; a message
  *from* an `f410f` account needs the RLP-encoded Ethereum transaction
  instead. See `filecoin.signature`.
- **Retries, backoff, connection pooling.** One request, one call. A caller
  who needs more can wrap `IHttp` — that is what the protocol is for.
- **Websockets.** Glif offers one; nothing here subscribes.

## Scope

**No message signed by this code has been sent to a network.** Every live
check above is a read. Sending needs a funded account, and the failure modes
of the write path are therefore still only argued for, not demonstrated.
