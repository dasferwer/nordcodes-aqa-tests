FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc
WORKDIR /project
COPY pom.xml .
RUN mvn -B -ntp -Dmaven.repo.local=/opt/m2 org.apache.maven.plugins:maven-dependency-plugin:3.8.1:go-offline \
    && mvn -B -ntp -Dmaven.repo.local=/opt/m2 org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get \
        -Dartifact=org.apache.maven.surefire:surefire-junit-platform:3.5.3
RUN curl -fsSLo /tmp/allure.tgz https://repo.maven.apache.org/maven2/io/qameta/allure/allure-commandline/2.32.0/allure-commandline-2.32.0.tgz \
    && curl -fsSLo /tmp/allure.sha512 https://repo.maven.apache.org/maven2/io/qameta/allure/allure-commandline/2.32.0/allure-commandline-2.32.0.tgz.sha512 \
    && printf '%s  /tmp/allure.tgz\n' "$(cat /tmp/allure.sha512)" | sha512sum -c - \
    && tar -xzf /tmp/allure.tgz -C /opt \
    && rm /tmp/allure.tgz /tmp/allure.sha512
COPY src ./src
RUN mvn -B -ntp -Dmaven.repo.local=/opt/m2 test-compile \
    && mkdir -p /app /home/tester \
    && chown -R 10001:10001 /project /home/tester
ENV HOME=/home/tester
ENV ALLURE_NO_ANALYTICS=true
USER 10001:10001
CMD ["sh", "-c", "mvn -B -ntp -o -Dmaven.repo.local=/opt/m2 -Dapp.jar=/app/application.jar test; test_status=$?; /opt/allure-2.32.0/bin/allure generate target/allure-results --single-file --lang ru --clean -o target/allure-report; report_status=$?; if [ $test_status -ne 0 ]; then exit $test_status; fi; exit $report_status"]
