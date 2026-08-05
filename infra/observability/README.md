# infra/observability/README.md
#
# Stub observability stack. Skeleton only — no tracing instrumentation is wired
# into the services yet; these files document the intended shape.
#
# Layout:
#   otel-collector-config.yml   OTLP receiver -> logging + OTLP/HTTP to Prometheus
#   prometheus/prometheus.yml   scrapes /actuator/prometheus on all 7 services
#   grafana/provisioning/       Prometheus datasource provisioning
#
# Services already expose Micrometer Prometheus metrics via
# `management.endpoints.web.exposure.include: health,info,prometheus` and
# `runtimeOnly io.micrometer:micrometer-registry-prometheus`.
#
# TODO: add OTLP SDK (tracing) dependency + `spring.application.name` propagation,
#       OTel Java agent in the service Dockerfiles, and Grafana dashboards.
