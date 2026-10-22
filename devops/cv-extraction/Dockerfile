ARG JAVA_RUNTIME=eclipse-temurin:21-jre-noble
FROM ${JAVA_RUNTIME}
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-vie tesseract-ocr-eng fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10002 --create-home extractor
WORKDIR /opt/extractor
COPY target/ttcs-backend-0.0.1-SNAPSHOT.jar /opt/extractor/backend.jar
COPY devops/cv-extraction/logback.xml /opt/extractor/logback.xml
USER 10002:10002
ENV OMP_THREAD_LIMIT=1
# The container must also have memory/CPU/PID/tmp limits; see README. timeout owns the entire process group.
ENTRYPOINT ["timeout", "--signal=TERM", "--kill-after=2s", "90s", "java", "-Xms32m", "-Xmx256m", "-XX:+ExitOnOutOfMemoryError", "-Djava.awt.headless=true", "-Dlogback.configurationFile=/opt/extractor/logback.xml", "-Dloader.main=vn.ttcs.recruitment.cvextraction.CvExtractionCommand", "-cp", "/opt/extractor/backend.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]
CMD ["/input/cv.pdf"]
