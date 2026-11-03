# Distro tùy chọn (glibc): ubuntu:22.04, debian:12, rockylinux:9, fedora:40 ...
ARG BASE_IMAGE=ubuntu:24.04

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B package -DskipTests

FROM ${BASE_IMAGE}

# JRE 21 lấy từ Temurin nên distro nào cũng chạy được, không cần có sẵn Java
COPY --from=eclipse-temurin:21-jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Tự nhận biết trình quản lý gói của distro
RUN set -eux; \
    if command -v apt-get >/dev/null 2>&1; then \
        apt-get update; \
        apt-get install -y --no-install-recommends libpcap0.8 curl iputils-ping bash ca-certificates iproute2; \
        rm -rf /var/lib/apt/lists/*; \
    elif command -v dnf >/dev/null 2>&1; then \
        dnf install -y libpcap iputils bash ca-certificates iproute; \
        command -v curl >/dev/null 2>&1 || dnf install -y curl; \
        dnf clean all; \
    elif command -v microdnf >/dev/null 2>&1; then \
        microdnf install -y libpcap iputils bash ca-certificates iproute; \
        command -v curl >/dev/null 2>&1 || microdnf install -y curl; \
        microdnf clean all; \
    elif command -v yum >/dev/null 2>&1; then \
        yum install -y libpcap iputils bash ca-certificates iproute; \
        command -v curl >/dev/null 2>&1 || yum install -y curl; \
        yum clean all; \
    else \
        echo "Distro khong duoc ho tro (can apt, dnf, microdnf hoac yum). Alpine khong ho tro." >&2; exit 1; \
    fi

WORKDIR /app
COPY --from=build /app/target/network-agent-1.0.0-SNAPSHOT.jar agent.jar
COPY demo-entrypoint.sh /app/demo-entrypoint.sh
# Xóa CRLF của Windows để script chạy được trên Linux
RUN sed -i 's/\r$//' /app/demo-entrypoint.sh && bash -n /app/demo-entrypoint.sh && chmod +x /app/demo-entrypoint.sh
ENTRYPOINT ["/app/demo-entrypoint.sh"]
