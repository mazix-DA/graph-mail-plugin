# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.3
Beveiligingsfixes in de gedeelde token-cache die in 1.0.2 werd geïntroduceerd.
- De cache-key voor de gedeelde `GraphTokenCache` bestond alleen uit `tenantId:clientId`, waardoor een
  pluginconfiguratie met een verkeerd of verouderd `clientSecret` een token kon hergebruiken dat een
  andere, correcte configuratie voor dezelfde tenant/client al had opgehaald en gecachet — zonder dat
  het secret opnieuw bij Azure Entra werd geverifieerd. De cache-key bevat nu een hash van het
  `clientSecret`, zodat een ander secret altijd een cache-miss geeft.
- `GraphCredentials` had geen eigen `toString()`, waardoor Kotlin's automatisch gegenereerde versie het
  `clientSecret` in cleartext zou tonen zodra het object ooit gelogd of geprint werd (bijv. een
  debug-logregel of een mislukte testassertion). `toString()` maskeert het secret nu altijd.

## 1.0.2
Vervolg op de security-hardening: architectuur- en betrouwbaarheidsverbeteringen.
- Token-cache verplaatst naar een gedeelde `GraphTokenCache`-bean, zodat caching daadwerkelijk werkt over
  plugin-instanties heen in plaats van per instantie opnieuw te beginnen.
- `GraphMailClient.sendMail`-interface vereenvoudigd naar `GraphCredentials`/`OutboundMail`-parameterobjecten.
- Frontend bouwt API-URL's nu via `ConfigService` in plaats van hardcoded paden, zodat de plugin blijft werken
  wanneer frontend en backend op verschillende origins draaien.
- Frontend-testinfrastructuur van de pluginlibrary hersteld (stond voorheen niet aangesloten).
- Publish-pipeline naar Maven Central gefixt (`cn.lalaki.central` correct toegepast op het pluginsubproject).

## 1.0.1
Correcties in de documentatie en kleine verbeteringen in de plugin.
- Codecommentaar bij de `send-email`-actie gecorrigeerd: deze vuurt op `SERVICE_TASK_START`, niet op
  `USER_TASK_CREATE` — en een procesvariabele is geen betrouwbare dedupe-guard bij een transactieretry,
  omdat die transactioneel is en samen met de retry terugrolt.
- Bijlagedrempel in de documentatie gecorrigeerd van 3 MB naar de daadwerkelijke 2 MiB.
- Toegevoegd: dat `Mail.Send`/`Mail.ReadWrite` applicatiemachtigingen zijn die alleen door een tenant-/Entra-beheerder
  met admin consent kunnen worden toegekend.
- Toegevoegd: een waarschuwing over `queue-size` bij de job-executor-configuratie — threads boven
  `core-pool-size` worden pas aangemaakt zodra de wachtrij vol is, dus een hoge `queue-size` maakt
  `max-pool-size` in de praktijk krachteloos.

## 1.0.0
Eerste publieke release: e-mail versturen via de Microsoft Graph API met OAuth2 (Client Credentials flow).
Inclusief verplichte `allowedSenders`-whitelist (deny-by-default): procesdata of procesbouwers kunnen geen
afzender kiezen die niet expliciet is toegestaan in de pluginconfiguratie.
