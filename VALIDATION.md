# Validação — 15/10/2026 — versão 0.3.1 — catálogo corrigido e mapa compartilhado

- Causa da indisponibilidade confirmada no APK 0.3.0: o AAPT transformou o asset de entrada `curitiba-network.json.gz` em `assets/curitiba-network.json`, descompactado. O carregador ainda solicitava o nome `.gz`. O pacote agora usa JSON e o carregador abre esse nome por streaming com buffer; o ZIP do APK fornece a compressão.
- `scripts/check_apk_assets.py` aprovado sobre o APK final: nomes solicitados pelo carregador, bytes dos dois assets, 75 bairros, 43.655 partes de ruas e versão do catálogo conferidos dentro do ZIP.
- Mapa, névoa e cobertura únicos entre carro, caminhada e bicicleta. A consulta reúne créditos antigos/novos e deduplica por rua/parte antes de somar. Real/simulado e versões da base continuam separados. O denominador é a união das vias, **6.331,757 km estimados**. Mesmos IDs e geometrias, base `curitiba-167e3e15fb46`; nenhum reset ou migração de banco.
- Painel movido para abaixo do seletor de atividade; mostra percentual geral e prévia de bairros mesmo sem GPS. Cada linha mantém percentual, barra e quilômetros.
- **45 testes JVM aprovados**, zero falhas/erros. Teste adicional verifica o denominador compartilhado sem multiplicar vias permitidas a várias atividades; reconhecimento ainda respeita cada modalidade.
- `python scripts/check_curitiba_data.py` e `python scripts/check_migration_sql.py` aprovados. O teste SQLite usa a consulta real do DAO: mesmo trecho em caminhada/bike conta 10 m, segundo trecho em carro eleva para 20 m; simulação e versão diferente não entram. Migrações 1→5, títulos, células, retenção e rollback continuam verificados.
- `:app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**. Lint: zero erros e 15 avisos. Sete testes instrumentados compilados; não executados porque `adb devices` não mostra aparelho. Não houve validação visual nem teste de GPS real nesta correção.

APK: `dist/expo-world-0.3.1-debug.apk`, versionCode 7, 64.562.030 bytes. SHA-256: `0B97C06168258C5B257585E4A383639BA15AD8A228980D8C930B198C02EBB2CC`.

Branch: `codex/curitiba-neighborhood-progress`. Configuração MapTiler local existente usada no APK, sem credenciais nos arquivos versionados.

---

# Validação — 15/10/2026 — versão 0.3.0 — Curitiba, caminhada e bicicleta

## Resultado atual

- **44 testes JVM aprovados**, sem falhas ou erros: 7 de cobertura por bairro, 19 de exploração, 13 de velocidade/rastro e 5 de progresso/resumo. A execução final reutilizou esses resultados porque o núcleo permaneceu inalterado.
- `:app:assembleDebug`, `:app:lintDebug` e `:app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**. Lint: **0 erros e 14 avisos**. O lint foi repetido após corrigir o escape do caminho do SDK em `local.properties`; a configuração e a chave continuam fora do Git.
- `python scripts/check_migration_sql.py`: aprovado. Migrações reais **1→2→3→4→5** comparadas ao schema Room 5 gerado pelo KSP; preservação de células e títulos, atividade antiga convertida para carro, deduplicação e separação de créditos por atividade/real-simulado/versão, reabertura, retenção e rollback conferidos em SQLite do computador.
- `python scripts/check_curitiba_data.py`: aprovado. **75 bairros**, **43.655 partes de vias**, referências válidas, geometrias finitas, remoção de duplicatas exatas, modalidades, versão e quilômetros por bairro consistentes com `data/curitiba/totals.json`.
- `git diff --check`: sem problemas de espaços.

Os testes do núcleo cobrem ruas parcialmente percorridas, ida/volta e repetição, janela de passos curtos, limites de movimento, precisão ruim, lacunas, deriva pequena, vias paralelas ambíguas, partes em bairros distintos e escala/parada de caminhada. O trajeto simulado de Curitiba passa inteiro pelo filtro sem saltos; coordenadas consecutivas duplicadas recebidas do serviço de rotas são removidas na preparação da geometria.

## Pacote e fontes

APK: `dist/expo-world-0.3.0-debug.apk` (também copiado para `dist/expo-world-mapa-debug.apk`), versão 0.3.0, versionCode 6. Build feito com a configuração MapTiler local existente, sem novo teste HTTP com a chave.

SHA-256 do APK: `CE6E0AB4F78CADEF56A6E57AB745CD7E978CB451984D619500406213765F9B37`.

Limites IBGE/Censo 2022, referência 31/07/2022; ruas OpenStreetMap, timestamp **2026-10-15T19:38:21Z**. Base `curitiba-167e3e15fb46`, distribuída em assets para funcionar sem consulta de cobertura online. Fonte, condições de uso, ODbL, método e limitações estão em `data/curitiba/README.md` e no README principal. O catálogo de vias é lido por streaming em um dispatcher de trabalho, indexado espacialmente e consultado apenas perto de cada deslocamento.

Totais estimados dos eixos elegíveis: carro 5.831,264 km; caminhada 6.084,683 km; bicicleta 6.106,774 km. Estes números não representam um levantamento de campo nem garantia de acesso, segurança ou completude.

## Pendente em Android

**Sete testes instrumentados compilados, não executados.** Incluem persistência, migração, isolamento, atividade dos trajetos salvos, deduplicação de ruas e leitura do catálogo com reconhecimento da simulação. `adb devices` retornou lista vazia. Não houve validação visual, benchmark de memória/desempenho, caminhada/bicicleta com GPS real nem teste de tela bloqueada nesta alteração.

A migração preserva os dados antigos, mas o progresso de ruas começa com novos registros. A névoa antiga não permite reconstruir com confiança a rua percorrida. As porcentagens são estimativas com partes de até 10 m, tolerância de GPS de 12 m, rejeição de ambiguidades e possíveis omissões/atribuições incorretas. Atualizar a versão da base futuramente requer remapeamento explícito; bases diferentes não misturam seus numeradores.

## GitHub

Branch: `codex/curitiba-neighborhood-progress`. Etapas enviadas durante o trabalho: `1d29434` (melhorias anteriores preservadas), `5ba6ef1` (base offline e motor de cobertura), `58683d0` (integração, modalidades, testes e migração), seguidas pelo commit deste registro. Chave, `local.properties`, caches, bancos temporários e APKs não foram versionados.

---

# Registro anterior — 15/10/2026 — simulação nas ruas e trajetos salvos

## Resultado desta alteração

- `:core:test`: **37 testes aprovados**, sem falhas ou erros (19 de exploração, 13 de rastro/velocidade e 5 de resumo/progresso).
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest` e `:app:lintDebug`: **BUILD SUCCESSFUL**. Lint: **0 erros, 10 avisos**.
- `python scripts/check_migration_sql.py`: aprovado; migrações **1→2→3→4** conferidas contra o schema 4 gerado pelo Room, preservação da exploração, reabertura, isolamento, retenção e rollback em SQLite do computador.
- `git diff --check`: sem problemas de espaços.

Novos testes JVM verificam rastro preto ao parar sem deslocamento, preservação de lacunas, curvas e coerência entre velocidade e deslocamento da simulação acelerada. A simulação inteira também passa pelo filtro sem saltos rejeitados.

Os **cinco testes instrumentados** foram compilados, mas não executados em Android. O novo teste verifica título, rejeição de percurso incompleto/título vazio/sessão substituída, preservação da cópia após nova viagem e limpeza da simulação, e reabertura do banco. Não confundir compilação com aprovação desses testes em aparelho.

## Ambiente e reprodução

JDK 17, SDK Platform 36 e Build Tools 35.0.0 instalados localmente em `.tools`. Neste Windows, o Java apresentou `Unable to establish loopback connection` antes da compilação. A configuração específica do diretório de sockets resolveu:

```powershell
New-Item -ItemType Directory -Force .tools/tmp | Out-Null
$env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=$PWD/.tools/tmp"
./scripts/bootstrap-build.ps1 -InstallAndroidSdk -AcceptAndroidSdkLicenses
python scripts/check_migration_sql.py
```

APK atual: `app/build/outputs/apk/debug/app-debug.apk`. Não havia `local.properties` neste checkout, portanto este APK usa o mapa neutro sem chave/estilo de tiles. A geometria simulada está incorporada; para conferir seu alinhamento visual com ruas, configure o provedor conforme o README e recompile.

## Pendente em aparelho

Verificar simulação 8× (tempo real depende do processamento), acompanhamento da câmera, rastro preto sem círculos, salvamento e seleção dos trajetos, atualização de banco v3 sem desinstalar e GPS real/segundo plano. Nenhuma validação visual ou de GPS real foi realizada nesta alteração.

---

# Registro histórico — 14/10/2026 — versão 0.2.0

O registro abaixo descreve a entrega anterior; seus APKs, hashes, limites de histórico e resultados não correspondem à alteração de 15/10 acima.

## Resultado

Executado com JDK 17, SDK Platform 36 e Build Tools 35.0.0:

```text
gradlew.bat :core:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL
```

- 35 testes JVM executados e aprovados: 19 de exploração, 11 de velocidade/rastro e 5 de progresso/resumo. Sem falhas, erros ou testes ignorados. Compilações posteriores reutilizaram resultados UP-TO-DATE.
- Cobertura inclui distâncias e revelação, geometria arredondada, precisão e saltos incompatíveis com velocidade, lacunas, parada, rastro ao vivo, área conforme latitude, preservação de área na subdivisão e resumo sem conectar lacunas.
- APK do app e APK dos testes Android compilados.
- Lint: 0 erros, 11 avisos. Há aviso do compilador sobre callback legado de localização.
- Assinatura verificada com apksigner: retorno 0. Certificado igual ao APK 0.1.3, permitindo atualização sem desinstalar.
- aapt: pacote br.com.expoworld, versão 0.2.0, versionCode 5, mínimo API 23, alvo API 36.

Entrega: dist/expo-world-0.2.0-debug.apk, 62.120.103 bytes.

SHA-256:

```text
BE33B003BDAA4AC645F382C98906CECEE6102187D463DBDC6C41CAAAD1101F0C
```

## Persistência e migração

scripts/check_migration_sql.py executado e aprovado. Extrai o SQL das migrações reais 1→2→3, compara com o schema Room 3 e verifica preservação de células positivas/negativas, subdivisão em quatro células, isolamento real/simulado, reabertura em disco, retenção e rollback transacional.

Esta verificação usa SQLite no computador. Room/KSP gerou os schemas e DAOs na compilação; não substitui testes de Room no Android.

Quatro testes instrumentados foram compilados, mas não executados. Cobrem persistência em disco, migração, retenção/isolamento e substituição atômica do percurso com snapshot ao vivo e progresso.

## Pendente no celular

Não houve instalação/abertura automatizada nem execução de connectedDebugAndroidTest. A última consulta ADB não encontrou aparelho conectado. Nenhuma captura Android desta versão foi produzida.

Verificar atualização preservando exploração; aparência e desempenho da névoa; rastro durante e depois da viagem; acompanhamento da câmera e gestos; resumo, marcos e simulação; GPS real, lacunas, bloqueio da tela, notificação, ausência de internet e permissões. Instalar como atualização, sem desinstalar, para preservar dados.

A filtragem mais rigorosa não melhora fisicamente o sensor GPS: rejeita pontos menos confiáveis e pode aumentar lacunas. A precisão e o comportamento em segundo plano dependem do aparelho e do Android.

## Provedor e ambiente

A chave permanece em local.properties ignorado e é usada pelo APK. Uma consulta MapTiler foi bloqueada anteriormente pela revisão automática por ausência de autorização explícita para transmitir a chave. Nenhum teste HTTP com essa chave foi concluído no computador. Carregamento do mapa, cotas e restrições precisam ser conferidos no celular.

JDK/SDK em .tools; cache Gradle em %TEMP%/expoworld-gradle. scripts/bootstrap-build.ps1 prepara o ambiente. APKs entregues ficam em dist, ignorado pelo Git.

## Limites

Faixa nominal de 50 m; grade de 10 m projetados, erro de discretização até cerca de 7,08 m. A abertura geométrica conservadora de 4 m arredonda bordas e pode reduzir pequenas regiões visíveis. Antialiasing ativado. Até 60 mil células por viewport, entre ±85° de latitude. Área exibida é estimada com correção por latitude.

Somente o último percurso por modo, até 20 mil amostras. Velocidade suavizada, paradas e resumo aproximados; lentidão não identifica a causa do trânsito. Velocidade desconhecida fica cinza. Sem mapas offline completos ou recuperação automática após morte/parada forçada do processo. Internet indisponível não deve impedir o registro local, mas essa condição ainda requer teste Android.
