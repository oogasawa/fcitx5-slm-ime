FROM ubuntu:24.04

# Ubuntu with GraalVM on top, as the coding standard fixes both.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*
ENV LANG=C.UTF-8
ENV GRAALVM_VERSION=25.0.0
RUN curl -fsSL "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${GRAALVM_VERSION}/graalvm-community-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz" \
      -o /tmp/graalvm.tar.gz \
 && mkdir -p /opt/graalvm \
 && tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1 \
 && rm /tmp/graalvm.tar.gz
ENV JAVA_HOME=/opt/graalvm
ENV PATH=${JAVA_HOME}/bin:${PATH}

# The workflow engine, the kana-kanji plugin (both of the current generation: the workflow YAML
# uses its argument rules), the workflow and the workers.
COPY jars /opt/kkime/jars
COPY kana-kanji-pairs.yaml worker.sh start.sh /opt/kkime/

USER 1000
CMD ["/opt/kkime/start.sh"]
