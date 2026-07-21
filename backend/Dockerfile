# CF-Training Backend コンテナイメージ（詳細設計 §13.3、技術選定書 §15.2）。
# ビルド用と実行用を分離したマルチステージビルド。JDK/ランタイムともAmazon Corretto 25へ統一。

# ---- build stage ----
FROM amazoncorretto:25 AS build
# gradlew は xargs（findutils）を必要とするが、Corretto最小イメージには含まれないため導入する。
RUN yum install -y findutils && yum clean all
WORKDIR /workspace

# 依存解決を層キャッシュに載せるため、まずビルド定義とWrapperのみコピー
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies >/dev/null 2>&1 || true

# ソースをコピーして bootJar を生成
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar

# ---- runtime stage ----
FROM amazoncorretto:25 AS runtime
# rootで実行しない（§12.5）。amazoncorretto:25（Amazon Linux 2023最小）は useradd を含まないため、
# パッケージマネージャに依存せず /etc/passwd へ直接追記して非rootユーザーを作成する。
RUN echo 'app:x:10001:10001::/home/app:/sbin/nologin' >> /etc/passwd \
    && echo 'app:x:10001:' >> /etc/group \
    && mkdir -p /home/app && chown 10001:10001 /home/app
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
USER 10001
EXPOSE 8080
# コンテナのメモリ制約に合わせた割合指定（§12.5）
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
