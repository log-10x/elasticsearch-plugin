FROM docker.elastic.co/elasticsearch/elasticsearch:8.17.0

# Install L1ES plugin
COPY build/distributions/l1es-plugin-0.9.0.es.8.17.0.zip /tmp/l1es-plugin.zip
RUN elasticsearch-plugin install --batch file:///tmp/l1es-plugin.zip

# Production defaults — override with -e flags or environment config as needed
# For local/dev testing, pass: -e "xpack.security.enabled=false" -e "discovery.type=single-node"
