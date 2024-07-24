(ns tesla-solar-charger.interfaces.sms)

(defprotocol SMSProcessor
  (process-sms [processor sms])
  )


