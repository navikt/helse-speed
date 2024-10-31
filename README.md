speed
============

Et mellomlager for PDL som bruker Redis for caching.

`speed-async` reagerer på identendringsmeldinger via PDLs LEESAH-topic og sørger for at cachen slettes for de
aktuelle identene.

## Komme i gang

For å komme kjapt i gang anbefales det å bruke `tbd-libs`.
Siste versjon finner du på [pakkeoversikten](https://github.com/navikt/tbd-libs/packages/2297521).

**build.gradle.kts**:
```kotlin
val tbdLibsVersion = "2024.10.30-17.50-ed013241"

dependencies {
    api("com.github.navikt.tbd-libs:azure-token-client-default:$tbdLibsVersion")
    api("com.github.navikt.tbd-libs:retry:$tbdLibsVersion")
    api("com.github.navikt.tbd-libs:speed-client:$tbdLibsVersion")
}
```

**applikasjonskode**:
```kotlin
val env = System.getenv()
val httpClient = HttpClient.newHttpClient()
val azure = createAzureTokenClientFromEnvironment(env)
val speedClient = SpeedClient(httpClient, jacksonObjectMapper().registerModule(JavaTimeModule()), azure)

// bruker retry-biblioteket for retries
val identer = retryBlocking { speedClient.hentFødselsnummerOgAktørId("identen man søker på", callId) }
```

## API

### Autentisering

Azure Bearer Token

### Call Id

Settes som `callId`-header. Viss det ikke er satt vil Speed lage en.

### Endepunkter

#### `POST /api/person` — opplysninger om en ident

##### Request Body
```json
{
  "ident": "[identen_man_søker_på. fnr/aktørId/npid]"
}
```

##### Response

###### HTTP 404 om identen ikke finnes
###### HTTP 500 ved feil
**Response body**:
```json
{
  "feilmelding": "en feilmelding som kan forklare problemet",
  "callId": "callId fra requesten"
}
```
###### HTTP 200 ved OK
**Response body**:
```json
{
  "fødselsdato": "1992-08-02",
  "dødsdato": null,
  "fornavn": "NORMAL",
  "mellomnavn": null,
  "etternavn": "MUFFINS",
  "adressebeskyttelse": "UGRADERT",
  "kjønn": "MANN"
}
```

---------

#### `POST /api/ident` — gjeldende identer

##### Request Body
```json
{
  "ident": "[identen_man_søker_på. fnr/aktørId/npid]"
}
```

##### Response

###### HTTP 404 om identen ikke finnes
###### HTTP 500 ved feil
**Response body**:
```json
{
  "feilmelding": "en feilmelding som kan forklare problemet",
  "callId": "callId fra requesten"
}
```
###### HTTP 200 ved OK
**Response body**:
```json
{
  "fødselsnummer": "02889298149",
  "aktørId": "2236655458597",
  "npid": null,
  "kilde": "CACHE"
}
```

---------

#### `POST /api/historiske_identer` — historiske identer

##### Request Body
```json
{
  "ident": "[identen_man_søker_på. fnr/aktørId/npid]"
}
```

##### Response

###### HTTP 404 om identen ikke finnes
###### HTTP 500 ved feil
**Response body**:
```json
{
  "feilmelding": "en feilmelding som kan forklare problemet",
  "callId": "callId fra requesten"
}
```
###### HTTP 200 ved OK
**Response body**:
```json
{
  "fødselsnumre": ["gammelt_fnr", "enda_eldre_fnr"]
}
```

# Henvendelser
Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på GitHub.

## For NAV-ansatte
Interne henvendelser kan sendes via Slack i kanalen #team-bømlo-værsågod.
