# Release notes

Overzicht van wijzigingen per versie van de Graph Mail-plugin.

## 1.0.2
- `jsoup` opgehoogd van 1.17.2 naar 1.23.1. 1.17.2 valt binnen het kwetsbare bereik van
  CVE-2026-71497 (Cleaner XSS-bypass via een misvormde tagnaam die eindigt op een controlekarakter,
  alleen uitbuitbaar bij een custom Safelist die raw-text-elementen toestaat). De `EMAIL_HTML_SAFELIST`
  van deze plugin voegt geen raw-text-elementen toe en was dus niet daadwerkelijk kwetsbaar, maar
  aangezien dit de bibliotheek is waar de HTML-sanitisatie van de plugin op leunt, is defensief
  opgehoogd naar de gepatchte versie.
- `TokenResponse` en de interne `CachedToken` van de token-cache hadden geen eigen `toString()`,
  waardoor Kotlin's automatisch gegenereerde versie het Graph API access-token in cleartext zou
  tonen zodra een van beide objecten ooit gelogd of geprint werd (bijv. een debug-logregel of een
  mislukte testassertion). `toString()` maskeert het token nu altijd.

## 1.0.1
Correcties in de documentatie en kleine verbeteringen in de plugin.

## 1.0.0
Eerste publieke release: e-mail versturen via de Microsoft Graph API met OAuth2 (Client Credentials flow).
