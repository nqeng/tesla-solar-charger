FROM clojure

RUN mkdir -p /tesla-solar-charger

COPY target/uberjar/tesla-solar-charger.jar /tesla-solar-charger
COPY ./GoSungrow /tesla-solar-charger/

WORKDIR /tesla-solar-charger

CMD ["java", "-jar", "./tesla-solar-charger.jar"]

