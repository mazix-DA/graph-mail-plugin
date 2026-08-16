# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.2
Vervolg op de security-hardening: architectuur- en betrouwbaarheidsverbeteringen.
- Token-cache verplaatst naar een gedeelde `GraphTokenCache`-bean, zodat caching daadwerkelijk werkt over
  plugin-instanties heen in plaats van per instantie opnieuw te beginnen.
- `GraphMailClient.sendMail`-interface vereenvoudigd naar `GraphCredentials`/`OutboundMail`-parameterobjecten.
- Frontend bouwt API-URL's nu via `ConfigService` in plaats van hardcoded paden, zodat de plugin blijft werken
  wanneer frontend en backend op verschillende origins draaien.
- Frontend-testinfrastructuur van de pluginlibrary hersteld (stond voorheen niet aangesloten).
- Publish-pipeline naar Maven Central gefixt (`cn.lalaki.central` correct toegepast op het pluginsubproject).

## 1.0.0
Eerste publieke release: e-mail versturen via de Microsoft Graph API met OAuth2 (Client Credentials flow).
Inclusief verplichte `allowedSenders`-whitelist (deny-by-default): procesdata of procesbouwers kunnen geen
afzender kiezen die niet expliciet is toegestaan in de pluginconfiguratie.
