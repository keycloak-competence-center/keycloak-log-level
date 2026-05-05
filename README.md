Keycloak Dynamic Log Level
===

This project provides a [Dynamic Log Level](./extensions/keycloak-log-level/README.md) extension for [Keycloak] as its only deliverable. It exposes a REST endpoint that lets a master-realm admin inspect and change Keycloak's log levels at runtime, without restarting the server.

**Target Environment:** ![Keycloak Version](https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fkeycloak-competence-center%2Fkeycloak-log-level%2Frefs%2Fheads%2Fmain%2Fpom.xml&query=%2F*%5Blocal-name()%3D'project'%5D%2F*%5Blocal-name()%3D'properties'%5D%2F*%5Blocal-name()%3D'keycloak.version'%5D%2Ftext()&label=Keycloak%20Version&color=blue)

### CI/CD Status

* **[Build Pipeline]**: [![Build Pipeline Status](https://github.com/keycloak-competence-center/keycloak-log-level/actions/workflows/build-pipeline.yml/badge.svg)][Build Pipeline]
* **[Release Pipeline]**: [![Release Pipeline Status](https://github.com/keycloak-competence-center/keycloak-log-level/actions/workflows/release-pipeline.yml/badge.svg)][Release Pipeline]

The following submodules have the artifact deployment to the maven repository skipped in their pom.xml:

- config
- container
- docker-compose
- helm
- server
- themes

The above submodules are only used during the development of the extension.

Project Template
---

This project is based on the [custom Keycloak template](https://github.com/inventage/keycloak-custom). It is structured as a multi-module Maven build and contains the following top-level modules:

- `config`: provides the build stage configuration and the setup of Keycloak
- `container`: creates the custom docker image
- `docker-compose`: provides a sample for launching the custom docker image
- `extensions`: contains the [Dynamic Log Level](./extensions/keycloak-log-level/README.md) SPI implementation.
- `helm`: provides a sample for installing the custom container image in Kubernetes using the Codecentric Helm Chart
- `server`: provides a Keycloak installation for local development & testing
- `themes`: provides samples for custom themes

Please see the tutorial [building a custom Keycloak container image](https://keycloak.ch/keycloak-tutorials/tutorial-custom-keycloak/) for the details of this project.

[Keycloak]: https://keycloak.org
[Build Pipeline]: https://github.com/keycloak-competence-center/keycloak-log-level/actions/workflows/build-pipeline.yml
[Release Pipeline]: https://github.com/keycloak-competence-center/keycloak-log-level/actions/workflows/release-pipeline.yml
