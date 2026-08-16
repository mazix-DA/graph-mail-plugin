# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.2
- Verplichte `allowedSenders`-whitelist toegevoegd (deny-by-default): elke verzending — ook via
  proces­data (`pv:`) — wordt geweigerd tenzij `senderMailbox` voorkomt op een kommagescheiden lijst
  van toegestane volledige adressen en/of `@domain`-entries in de pluginconfiguratie. Geldt ook voor
  het test-send endpoint. Bestaande pluginconfiguraties weigeren na de upgrade elke verzending totdat
  de whitelist eenmalig is ingevuld en opgeslagen.

## 1.0.1
Correcties in de documentatie en kleine verbeteringen in de plugin.

## 1.0.0
Eerste publieke release: e-mail versturen via de Microsoft Graph API met OAuth2 (Client Credentials flow).
