# clickup

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the whole stack

```shell script
docker compose up -d --build
```

That builds a GraalVM native image of the API inside a build container and starts it
alongside Postgres. Nothing but Docker is needed. **The first build takes roughly ten to
fifteen minutes** — it downloads the entire Maven dependency tree inside the container,
because it cannot see your `~/.m2`. Later builds reuse a BuildKit cache mount and are much
faster.

| Service | Where |
| --- | --- |
| API | <http://localhost:7575> |
| Readiness | <http://localhost:7575/q/health/ready> |
| Postgres | `localhost:5432`, user/password `clickup` |

**Two databases on one server.** `clickup` is the development one, seeded with 30 rows per
table, and it is what `mvn quarkus:dev` and the test suite use. The API container gets
`clickup_api` instead — created empty by the `api-db` one-shot on first `up`, and it stays
that way, which is what a production database actually looks like: no demo data and one
administrator. Keeping them apart is not tidiness; an API pointed at `clickup` would add
its bootstrap admin as a 31st user and fail a test in a package that has nothing to do
with deployment.

The API comes up with **one administrator**, created on the first start against an empty
database: student id `10000000`, password `ChangeMe123`. Change that password through the
API immediately — it sits in `docker-compose.yml` and in the container's environment, where
`docker inspect` reads it. Override the defaults with `ADMIN_STUDENT_ID`, `ADMIN_PASSWORD`
and `ADMIN_NAME` in a `.env` file next to the compose file.

The account exists because nothing else can create one: the demo seed is filtered out of
production and `POST /api/auth/register` only ever makes students, so without it
`POST /api/logs` — which admits administrators and nobody else — would be unusable by
everybody.

### What the stack does not do

**It terminates no TLS.** The API listens on plain `7575` and expects something in front of
it to hold a certificate. Set `QUARKUS_TLS_KEY_STORE_P12_PATH` and
`QUARKUS_TLS_KEY_STORE_P12_PASSWORD` to have it bind `7443` as well.

**It generates its own signing key.** With no `JWT_PRIVATE_KEY_LOCATION` set, the
application writes an RSA-2048 keypair into the `clickup-keys` volume on first start and
uses it from then on. Unique per deployment, absent from the image, stable across restarts —
but nobody chose it and nobody rotates it, and anyone who can read that volume can forge an
access token for any account. Point `JWT_PRIVATE_KEY_LOCATION` and
`JWT_PUBLIC_KEY_LOCATION` at a key you manage for anything that matters.

### State

Three named volumes hold everything the application creates and cannot recreate. Losing the
last one logs every user out at once; losing the other two is silent.

| Volume | Holds |
| --- | --- |
| `clickup-uploads` | files from `POST /api/upload` |
| `clickup-logs` | `app.log` and `error.log` from `POST /api/logs` |
| `clickup-keys` | the JWT signing keypair |

`docker compose down` keeps all of them. `docker compose down -v` destroys all of them,
along with the database.

### Just the database

The test suite and `mvn quarkus:dev` run against the compose Postgres, seeded with 30 rows
per table, and do not want the API container:

```shell script
docker compose up -d postgres
mvn quarkus:dev
```

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/clickup-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Provided Code

### REST

Easily start your REST Web Services

[Related guide section...](https://quarkus.io/guides/getting-started-reactive#reactive-jax-rs-resources)
