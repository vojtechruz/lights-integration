# Lights Integration — Analýza features

Program běžící na pozadí, který sleduje stav mých AI agentů (primárně Claude Code)
a v reálném čase mění barvu / jas Philips Hue světel podle toho, co agent zrovna dělá.
Cíl je ambientní periferní přehled — z rohu oka poznám „agent na mě čeká", „agent maká",
„něco selhalo", aniž bych koukal do terminálu.

Vývoj jde postupně: **MVP teď**, ale architektura má čisté švy pro pozdější rozšíření
(jiný poskytovatel agentů, jiný poskytovatel světel, další zdroje jako Pomodoro).

---

## 1. Rozhodnutí (zafixováno)

| Téma | Rozhodnutí |
|---|---|
| Jazyk / platforma | **Java 25 (poslední LTS)** |
| Build | **Gradle** (poslední stabilní, přes wrapper) |
| Běhový model | **CLI, které nastartuje dlouhoběžící daemon** |
| Monitoring agentů | **Claude Code hooky** posílají lehký HTTP request na daemon (např. přes `curl`) |
| Ovládání světel | Jediný zapisovatel do Hue (**single writer**) — vše ostatní jen posílá události |
| Agregace více agentů | **Nejhorší stav aspoň jednoho agenta** (max-severity dle hierarchie) |
| `long_running` práh | **Konfigurovatelný, default 5 minut** |
| První světla | **Světla za monitorem** přes Hue bridge |

Proč daemon + tenký hook: světla musí reagovat průběžně a někdo musí držet stav. Hooky
neposílají spouštění celého programu (JVM start při každém tool-callu by byl pomalý),
jen lehký HTTP request na už běžící daemon. Tím je volba Javy nezávislá na startovací
latenci hooků.

---

## 2. Doménový model

| Koncept | Popis |
|---|---|
| **Agent** | Jedna sledovaná AI instance (Claude Code session). Má identitu a aktuální stav. |
| **Agent State** | Diskrétní stav agenta, ze kterého se odvozuje světelný efekt. |
| **Status Source** | Zdroj událostí o stavu (Claude Code; později Pomodoro, další nástroje). |
| **Zone** | Váže sadu zdrojů → jedno cílové světlo + mapování stavů. |
| **Light Target** | Konkrétní Hue světlo / skupina (i více sub-světel). |
| **Light State** | Vendor-neutrální popis světla (barva, jas, efekt, přechod). |
| **Mapping** | Pravidlo `stav → light state`. |
| **Aggregator** | Z více agentů složí jeden výsledný stav (nejhorší vyhrává). |

---

## 3. Architektura připravená na expanzi

Jádro nezná ani Claude, ani Hue — mluví pouze v abstrakcích.

```
[Sources]            [Core engine]                 [Outputs]
Claude Code  ─┐                                  ┌─ Hue (bridge)
Pomodoro     ─┼─► StatusEvent ─► Zone ─► Mapping ─┼─ HDMI sync box (později)
(další)       ─┘        │          │       │      └─ (další vendor)
                        ▼          ▼       ▼
                  Aggregator   Router   LightState (barva/jas/efekt, vendor-agnostic)
```

### Klíčové abstrakce

| Abstrakce | Role | První implementace |
|---|---|---|
| `StatusSource` | Produkuje události o stavu | `ClaudeCodeSource` (HTTP ingest z hooků) |
| `AgentState` | Doménový enum stavů | viz hierarchie níže |
| `Aggregator` | Vybere podle hierarchie „nejhorší vyhrává" | max-severity |
| `Zone` | Váže zdroje → cílové světlo + mapování | `AgentsZone` → světlo za monitorem |
| `LightState` | Vendor-neutrální popis světla | — |
| `LightProvider` | Přeloží `LightState` na konkrétní světla | `HueProvider` |
| `LightTarget` | Cíl (světlo/skupina, i více sub-světel) | monitor light |

**Švy pro budoucnost:**
- Jiný poskytovatel agentů → nová implementace `StatusSource`.
- Jiný vendor světel → nová implementace `LightProvider`.
- Nové routování zdroj → světlo → nová `Zone`.

Tyto abstrakce zavedeme **hned v MVP**, ale každou jen s jednou implementací.

---

## 4. Stavy agenta a hierarchie agregace

Když běží víc agentů, světlo ukazuje **nejhorší stav, který má aspoň jeden agent**
(max-severity). Pořadí od nejzávažnějšího:

```
ERROR > WAITING_FOR_INPUT > LONG_RUNNING > RUNNING > SUCCESS > IDLE
```

Příklad: agent A má `ERROR`, agent B má `WAITING_FOR_INPUT` → světlo ukazuje `ERROR`.

### Návrh mapování (konfigurovatelné)

| Stav | Význam | Návrh barvy / efektu |
|---|---|---|
| `ERROR` | Chyba / selhání | Červená |
| `WAITING_FOR_INPUT` | Čeká na vstup / potvrzení permissions | Oranžová/žlutá (upozornění) |
| `LONG_RUNNING` | Běží déle než práh (default 5 min) | Fialová / pomalé blikání |
| `RUNNING` | Agent pracuje / spouští nástroj | Modrá (příp. jemné pulzování) |
| `SUCCESS` | Úkol dokončen | Zelená — **krátký záblesk**, pak návrat |
| `IDLE` | Nic neběží | Tlumená bílá / zhasnuto |
| (žádný/neznámý agent) | Nic se nesleduje | Zhasnuto |

Poznámka: `SUCCESS` je transientní (problikne a zóna spadne zpět), jinak by zelená
svítila, dokud daný agent nepřejde na `IDLE`.

---

## 5. MVP scope

1. Daemon spouštěný přes CLI: `pair`, `test`, `run`.
2. Lokální HTTP ingest endpoint pro příjem událostí.
3. `ClaudeCodeSource` napájený z hooků přes `curl`.
4. Stavy: `RUNNING`, `WAITING_FOR_INPUT`, `ERROR`, `IDLE`, `LONG_RUNNING` (+ `SUCCESS` záblesk).
5. Agregace podle hierarchie (nejhorší vyhrává).
6. Jedna zóna → světlo(a) za monitorem přes `HueProvider`.
7. Config soubor (bridge IP + klíč, cílová světla, mapování stavů, `long_running` práh)
   + párování bridge a bezpečné uložení API klíče lokálně.

Abstrakce `StatusSource` / `LightProvider` / `Zone` zavedeme hned, s jednou implementací každé.

---

## 6. Integrace s Philips Hue

- **Objevení bridge**: ruční IP pro MVP; auto-discovery (mDNS / cloud) později.
- **Autentizace**: párování stiskem link tlačítka → uložení application key lokálně.
- **Ovládání světel**: on/off, jas, barva, plynulé přechody; efekty (pulzování/blikání).
- **Výběr cílů**: konkrétní světla nebo skupina/room.
- **Rate limiting**: bridge má limity na počet příkazů/s → debounce a slučování změn.
- **Obnova stavu**: při ukončení daemona vrátit světla do původního stavu (později).

Konkrétní syntaxi Hue API (CLIP v2, formát barev, autentizace) ověříme z aktuální
dokumentace při implementaci.

---

## 7. Světla za monitorem (dvě části)

Fixture se skládá ze dvou částí, které možná lze ovládat zvlášť — v Hue se to nejspíš
jeví jako dvě adresovatelná světla. `LightTarget` proto navrhneme tak, aby uměl cílit
i na víc sub-světel. Skutečnou adresovatelnost zjistíme při párování. Pro MVP je klidně
můžeme řídit společně a rozdělení přidat později.

---

## 8. Roadmapa rozšíření (po MVP)

### Pomodoro (další zdroj, ne samostatný nástroj na světla)
- Model přes koncept **Zón**: zóna „Agenti" → světlo za monitorem, zóna „Pomodoro" → jiné světlo.
- Časovač může zůstat v Obsidianu (plugin, který jen POSTuje události na náš ingest);
  **náš daemon zůstává jediným zapisovatelem do Hue**, aby se nástroje o bridge nepraly.
- Stavy typu „working / přestávka / zbývá posledních X minut" řešíme jako efekty
  v mappingu té zóny.

### TV světla přes HDMI krabičku (samostatný provider později)
- Pravděpodobně **Philips Hue Play HDMI Sync Box** — má vlastní režim: při aktivní
  synchronizaci obrazu si světla přebírá, takže náš ambientní režim by koloval.
- Řešíme jako samostatný `LightProvider` s odlišným chováním až po monitoru.

### Další
- Auto-discovery bridge, per-agent světla, dynamické efekty, systray, obnova původního
  stavu světel, podpora dalších agent nástrojů než Claude Code.

---

## 9. Nefunkční požadavky

- **Odolnost**: bridge nedostupný / síť vypadne → retry, daemon nespadne.
- **Latence**: reakce světla v řádu stovek ms.
- **Rate limiting**: debounce a slučování změn kvůli limitům bridge.
- **Bezpečnost**: Hue klíč jen lokálně, komunikace pouze v LAN.
- **Rozšiřitelnost**: nový zdroj stavů nebo nový typ světla bez přepisu jádra.

---

## 10. Otevřené body k potvrzení před implementací

1. **Hooky v Claude Code** — OK přidat konfiguraci hooků do nastavení Claude Code, aby
   posílaly události na daemon?
2. **Hue přístup** — je už k dispozici IP bridge a API klíč, nebo začneme párováním od
   nuly (link tlačítko)?
