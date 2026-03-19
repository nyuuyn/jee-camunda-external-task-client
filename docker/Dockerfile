# ── Stage 1: Configure WildFly ────────────────────────────────────────────────
FROM quay.io/wildfly/wildfly:32.0.0.Final-jdk17 AS builder

# Download the PostgreSQL JDBC driver
RUN curl -L \
    "https://jdbc.postgresql.org/download/postgresql-42.7.3.jar" \
    -o /tmp/postgresql.jar

# Stage the driver in a known location for the CLI script
RUN mkdir -p /opt/jboss/wildfly/customization
RUN cp /tmp/postgresql.jar /opt/jboss/wildfly/customization/postgresql.jar

# Apply WildFly configuration (adds module + datasource to standalone.xml)
COPY docker/configure-wildfly.cli /tmp/configure-wildfly.cli
RUN /opt/jboss/wildfly/bin/jboss-cli.sh --file=/tmp/configure-wildfly.cli

# ── Stage 2: Runtime image ────────────────────────────────────────────────────
FROM quay.io/wildfly/wildfly:32.0.0.Final-jdk17

# Copy the pre-configured WildFly from the builder stage
COPY --from=builder /opt/jboss/wildfly /opt/jboss/wildfly

# Deploy the application WAR
COPY notes-war/target/notes.war /opt/jboss/wildfly/standalone/deployments/

EXPOSE 8080 9990

CMD ["/opt/jboss/wildfly/bin/standalone.sh", \
     "-b", "0.0.0.0", \
     "-bmanagement", "0.0.0.0"]