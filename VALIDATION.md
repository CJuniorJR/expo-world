# Validação — 15/10/2026 — simulação nas ruas e trajetos salvos

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
