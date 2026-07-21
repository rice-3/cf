# CF-Training Backend コンテナイメージ（詳細設計 §13.3、技術選定書 §15.2）。
# ビルド用と実行用を分離したマルチステージビルド。JDK/ランタイムともAmazon Corretto 25へ統一。

# ---- build stage ----
FROM amazoncorretto:25 AS build
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
# rootで実行しない（§12.5）。amazoncorretto:25 は Amazon Linux 2023 ベース。
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
USER 10001
EXPOSE 8080
# コンテナのメモリ制約に合わせた割合指定（§12.5）
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
