FROM clojure

RUN mkdir -p /tesla-solar-charger

COPY target/uberjar/tesla-solar-charger.jar /tesla-solar-charger
COPY ./GoSungrow /tesla-solar-charger/GoSungrow-home
COPY ./GoSungrow /tesla-solar-charger/GoSungrow-office

WORKDIR /tesla-solar-charger

CMD ["java", "-jar", "./tesla-solar-charger.jar"]

