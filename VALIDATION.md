# Validação — 16/10/2026 — 0.6.1 — Interface e GIF

- Menu dividido em Explorar, Bairros, Trajetos e Ajustes. Seletores fixos e conteúdo rolável independente; acesso direto a Planejar volta e Bairros no mapa. Replay, resumo, cartão e trajetos salvos aparecem antes dos detalhes expansíveis de velocidade.
- Durante a viagem, Pausar/Continuar assume o botão principal. Encerrar fica separado e abre confirmação tanto no mapa como no menu; dispensar o aviso mantém o estado da gravação. A confirmação é apresentada sobre os outros painéis. Não altera o serviço de GPS, as ações da notificação, o banco nem o tema escolhido.
- GIF 720×960, 100 quadros a 10 qps, pausa final de 1,5 s e ciclo de 11,4 s. Antisserrilhamento, paleta adaptativa global de 256 cores (estável, com preto e cores essenciais reservados) e LZW com dicionário limitado a 4096 entradas. Mesmo mapa/projeção do PNG, um bitmap de animação reutilizado e codificação fora da interface.

## Verificações

- **80 testes JVM aprovados**, zero falhas/erros/ignorados. Três novos: mudanças de largura dos códigos LZW e resets com pixels aleatórios nas paletas de 16/256 cores; redução do erro de cor em tons sintéticos de mapa e preservação das cores essenciais; GIF completo de 100 quadros em 720×960 lido por ImageIO, com dimensões, todos os pixels dos quadros 0/49/99 e duração de todos os quadros conferidos.
- Os testes de compressão incluem área uniforme em resolução final e limite de tamanho para uma animação sintética. Não representam benchmark de exportação Android nem medição com um mapa real.
- `:core:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`: BUILD SUCCESSFUL. Lint: 0 erros, 34 avisos. Os 12 testes instrumentados anteriores compilaram, mas não foram executados: nenhum dispositivo listado por ADB.
- Assets conferidos dentro do APK e `git diff --check` aprovado. Sem migração ou alteração na base offline; configurações locais, chave, caches e APK permanecem ignorados no Git.

APK: `dist/expo-world-0.6.1-debug.apk`, versionCode 16, 65300893 bytes. SHA-256: `8FE2AFAAAFA674E07E820CE58E03845728427D2434BDD355814F33176D265149`.

Entrega na main conforme autorização já existente. Pendentes no aparelho: legibilidade com fonte ampliada/tela pequena, troca das quatro áreas, confirmação/cancelamento no mapa e no menu, pausa/continuação e exportação PNG/GIF com mapas carregados. GIF continua limitado a 256 cores e pode demorar mais em celulares modestos. Não houve captura de tela Android nem teste real de GPS nesta rodada. Instalar como atualização, preservando dados.

---

# Validação — 16/10/2026 — 0.6.0 — Exploração de bolso

- Implementados planejamento por tempo (10–180 min), Explorar/Passear, perfis caminhada/bicicleta/carro, partida GPS ou toque no mapa, circuitos com retorno incluído e roteiros abertos. Compara até três candidatos e tenta um ajuste de duração. Explorar favorece mais quilômetros novos estimados; Passear favorece duração, continuidade e menos mudanças de direção, com opções de custo para menos subidas/rodovias.
- Prévia tracejada independente do rastro real, orientações em português, início perto da partida com o GPS/serviço existentes e retorno sob demanda à **origem original**, mesmo após vários recálculos. Roteiro anterior conservado em falha/cancelamento. Consulta de retorno disponível também na prévia de um roteiro salvo, antes de iniciar gravação. Sem voz, recálculo automático ou promessa de tempo exato.
- Roteiros planejados nomeados e separados dos trajetos realizados, com reabertura/exclusão e atualização local da estimativa de descoberta ao reutilizar. Salvar/abrir/gerar não concede créditos. Banco 7→8 cria somente planned_routes; todas as variantes antigas mantêm o caminho de atualização.
- O usuário autorizou explicitamente o envio da partida e pontos sugeridos ao Valhalla/FOSSGIS após a revisão automática pedir autorização. Uso identificado na interface e README. Nenhum histórico de GPS, créditos ou névoa é enviado. Chamadas serializadas, 1,2 s entre inícios, máximo quatro por geração, HTTPS sem seguir redirecionamentos, resposta até 2 MB. Endpoint público apenas em debug para testes pessoais; release precisa configurar serviço autorizado.

## Verificações

- **77 testes JVM aprovados**, zero falhas/erros. Seis testes novos cobrem circuitos/rotas abertas nos três modos, variação de opção, orçamento com retorno, rejeição de duração/partida inadequadas, priorização de quilômetros novos e poucas curvas, decoder polyline6 com limites/entrada corrompida, progresso sem pular direto à chegada em circuito e validação de coordenadas/tempo.
- `:core:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**. Lint: 0 erros, 34 avisos. **12 testes instrumentados compilados, não executados**, pois adb não mostra aparelho. Os dois novos verificam os três perfis com fixtures públicas e persistência/exclusão de roteiro sem conceder exploração.
- Três chamadas reais e espaçadas ao servidor autorizado, com coordenadas públicas fixas do centro de Curitiba: pedestrian, bicycle, auto. Todas retornaram três pernas conectadas e pt-BR. Resultados: 2,673 km/1985,301 s (pé); 3,285 km/1000,968 s (bike); 4,866 km/915,974 s (carro). São verificações da API com um circuito público, **não execução completa da interface Android**. Respostas preservadas em assets de testes com atribuição OSM/Valhalla.
- `scripts/check_migration_sql.py`: nove variantes históricas normalizadas e comparadas ao schema Room 8; dados/índices/defaults preservados; gravar roteiro não altera células, sessões, amostras nem créditos. Verificação em SQLite do computador, sem substituir teste Room no aparelho.
- `scripts/check_curitiba_data.py`, `scripts/check_apk_assets.py` e `git diff --check`: aprovados. A base de 75 bairros permanece inalterada e os nomes/bytes foram conferidos no APK.

APK: `dist/expo-world-0.6.0-debug.apk`, versionCode 15, 65341133 bytes. SHA-256: `501DF1CC46B61E0B21789E99143A5A9607034315A71C84376EAFA1E89A326A95`.

Entrega diretamente na main conforme pedido: primeira etapa enviada em `5689cd2`; integração completa registrada no commit deste relatório. Chave e configuração locais, caches e APK fora dos commits.

Pendentes no aparelho: aparência, escolha de origem no mapa, geração completa e ajuste de duração, início/gravação real, orientações, retorno repetido, cancelar/repetir consulta, reutilização, atualização com dados existentes e comportamento sem rede. Estimativas sujeitas aos dados OSM, precisão do GPS e disponibilidade do demo; roteiro salvo não garante condições atuais das vias. O backup de células não inclui roteiros planejados.

---

# Validação — 16/10/2026 — versão unificada 0.5.1

## Integração e regressões

- Todas as referências locais e remotas de `origin` conferidas. A branch `codex/curitiba-neighborhood-progress` já estava na main pelo PR 1; a branch `codex/exploration-replay-v0-5` apareceu no fetch seguinte e foi integrada com resolução manual dos conflitos. A entrega segue a instrução mais recente do usuário: main direta, sem novo PR.
- Preservados os recursos de ambas: bairros/modalidades/temas/Palio/trajetos nomeados e backup/pausa/recuperação/área nova/visão geral/comparação/replay/primeiras datas/exportação.
- Percentuais de bairros novamente no menu, compartilhados entre atividades e com catálogo offline correto no APK.
- Camada `speed-stop-dots` e fonte de marcadores de parada removidas; rastro preto nas paradas preservado. Marcadores de início/fim e posição do replay têm finalidade distinta e permanecem.
- PNG, GIF e prévia agora usam um snapshot MapLibre com o estilo configurado, alinhando o rastro à projeção do mapa e mantendo a atribuição. GIF passou a 256 cores. Snapshot requer internet/dados disponíveis; falha/tempo limite de 45 s gera orientação, sem exportação silenciosa só da linha. O GIF anima o rastro sobre o mapa, sem a névoa animada do replay interno.

## Verificações executadas

- **71 testes JVM aprovados**, zero falhas/erros. Incluem testes de ambas as branches, decodificação independente dos GIFs com 16 e 256 cores e cartão com projeção de mapa, atribuição e parada preta sem símbolos de parada.
- `:core:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**. Lint: 0 erros, 32 avisos. Dez testes instrumentados compilados, não executados (`adb devices` sem aparelho).
- `scripts/check_migration_sql.py`: **oito variantes históricas** (1/2/3/4/5/6 e os outros schemas 4/5) normalizadas para o schema Room 7, com preservação de células, títulos, rastros, datas, células de viagem e créditos. Colunas/defaults/índices comparados ao schema gerado; normalização repetível e consultas de ruas sem duplicidade entre modalidades verificadas em SQLite do computador. O script não substitui execução Room no Android.
- `scripts/check_curitiba_data.py` e `scripts/check_apk_assets.py`: aprovados; 75 bairros, 43.655 partes de ruas, mesmos IDs/versão e assets verificados dentro do APK final.
- `git diff --check`: aprovado após resolução dos conflitos.

APK: `dist/expo-world-0.5.1-debug.apk`, versionCode 14, 64725891 bytes. SHA-256: `A7B62E528537F90668609A9DE5B681D849C4FAA6ECE257B4B2E01C5B5C94B2A4`.

Não houve execução visual/GPS no Android nem renderização do snapshot MapLibre em aparelho nesta sessão. Precisam ser conferidos no dispositivo: atualização sem desinstalar, abertura do banco, ruas no PNG/GIF, atribuição legível, paradas pretas e bairros no menu. O backup herdado preserva células/datas; não inclui rastros, trajetos nomeados, créditos de ruas por bairro ou preferências.

---

# Registros anteriores (históricos)

# Validação — 15/10/2026 — versão 0.3.2 — temas prontos

- Sete temas: Verdinho, Azul, Vermelho, Rosa, Amarelo, Branco e Preto. O **verdinho original continua padrão**, com a mesma pintura do Palio e fórmula/cores do degradê. Branco usa interface clara; demais temas usam fundos escuros.
- Seletor no menu, alteração imediata de interface, bitmaps do Palio/pedestre/bike, destaque dos bairros, rastro e legenda. Escolha persistida em SharedPreferences; chave ausente/desconhecida retorna ao verde. Não reinicia câmera/estilo, não muda progresso, não altera banco ou amostras dos trajetos salvos. Paradas pretas e lacunas preservadas.
- **49 testes JVM aprovados**, zero falhas/erros. Quatro testes de tema verificam fallback e sete chaves, paleta original, paradas/velocidade desconhecida em todos os temas e modalidades, recoloração do GeoJSON sem pontos ou conexão de lacunas.
- `:core:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`: **BUILD SUCCESSFUL**. Lint: 0 erros, 17 avisos. Sete testes instrumentados compilados, não executados; sem Android conectado para validar aparência e persistência ao reabrir.
- `python scripts/check_apk_assets.py`: aprovado sobre o APK final; catálogo de Curitiba e nomes de assets preservados. `git diff --check`: aprovado.

APK: `dist/expo-world-0.3.2-debug.apk`, versionCode 8, 64578421 bytes. SHA-256: `E4233E8EE3BB6F073EF933A231434AFDF0DF0B0B96A4ED8B5DFF94A65C3F4E68`.

Branch: `codex/curitiba-neighborhood-progress`. Configuração local MapTiler usada, sem credenciais nos commits.

---

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
# Validação — 15/10/2026 — versão 0.5.0

## Resultado executado

```text
gradlew.bat :core:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL
```

JDK 17, Gradle 8.13, SDK Platform 36, Build Tools 35.0.0. Compilação final em 12 s.

- **55 testes JVM executados e aprovados**, sem falhas/erros/ignorados. São 47 anteriores e oito novos: cinco de replay/comparação/épocas/backup/Curitiba, dois de arte/GIF e um de limite de bytes do backup com datas.
- Replay: interpolação somente em segmentos contínuos, preservação das lacunas e dos dados originais; células não reveladas antes de seu tempo e ordem antiga desconhecida.
- Comparação: máscara geográfica recortada, com diferença entre região nova e região fora da diferença; sem gravação no banco.
- Datas: limite mensal, datas ausentes/futuras, prioridade do último percurso, backup 2 com datas e leitura do backup 1.
- Curitiba: todos os 121 pontos normais passam pelo filtro; coordenadas dentro da região e mudanças de direção verificadas. Os testes existentes de velocidade, parada e cenários adversos também passaram com o novo trajeto curvo.
- GIF: saída do encoder lida por ImageIO, decodificador independente no computador; dois quadros com 33×19 pixels, todas as cores/pixels conferidos, incluindo múltiplos sub-blocos e resets de LZW.
- Cartão: limites geométricos, texto escapado na prévia SVG e título vazio. Geradas prévias PNG/SVG no computador a partir dos mesmos comandos da arte e inspecionada a PNG visualmente. **Não é captura Android**; usa Java2D/fontes do computador. A exportação de produção usa Canvas Android.
- Backup: limite de bytes na geração impede criar arquivo com datas acima do limite aceito pela restauração.
- App e APK de teste Android compilados. Room/KSP gerou schema 6 e DAOs.
- **Lint: 0 erros, 26 avisos**. Avisos de dependências/ferramentas, backup legado e sugestões KTX para Canvas/Bitmap/cores/preferências. Há aviso de callback legado no compilador. Não foram atualizadas dependências.
- scripts/check_migration_sql.py executado e aprovado: migrações 1→2→3→4→5→6 compatíveis com o schema exportado, datas e ordem antigas desconhecidas, primeira data preservada em duplicatas, células/rastros, paginação, isolamento, área sem duplicação e rollback.
- O script Python usa SQLite no computador, não Room no Android.
- Assinatura do APK verificada com apksigner: retorno 0 e mesmo certificado de 0.4.0.
- aapt: br.com.expoworld, versionCode 8, versionName 0.5.0, mínimo API 23 e alvo API 36.

## APK e prévia

`dist/expo-world-0.5.0-debug.apk` — 62.311.200 bytes.

SHA-256:

```text
9767324D3D3CC0A9B14253C8A4C628DD6BCFEE132474A11499A0EEF13C97CE3D
```

Instalar como atualização, sem desinstalar. `dist/previa-cartao-curitiba.png` é uma prévia ilustrativa com dados simulados e área de exemplo, gerada no computador. O total mostrado nessa prévia não é resultado de um teste GPS real.

Chave, ferramentas, caches, APKs e arquivos de backup permanecem fora do Git. Foram consultadas ruas públicas numa pequena área pela API OpenStreetMap, sem chave ou dados pessoais. Nenhuma consulta HTTP usando a chave MapTiler foi executada no computador.

## Android pendente

adb devices -l executado nesta rodada: nenhum aparelho/emulador. **Sete testes instrumentados compilados, mas não executados.** O novo teste verifica primeira data preservada em revisitas, data antiga nula e tempo de revelação do último percurso ao começar outra viagem.

Não houve instalação, abertura automatizada, captura Android, exportação real por URI nem benchmark de FPS/bateria/memória. Validar no aparelho:

1. Atualização preservando banco antigo. Curitiba na abertura sem GPS válido, sem enquadrar automaticamente um rastro antigo de São Paulo. GPS real e Enquadrar percurso continuam funcionando.
2. Nova simulação normal em Curitiba com curvas e parada. Conferir todos os cenários adversos, pausa/continuação e isolamento de real/simulado.
3. Replay: reprodução, pausa, busca, sair, ida/volta do segundo plano e troca de modo. Não ligar lacunas nem alterar progresso persistido. Conferir névoa no detalhe e fallback de rastro em visão ampla/viagem antiga.
4. Comparação: barra no início/fim/meio, pan/zoom/rotação, coerência da divisória, limites de células e antimeridiano. Importações posteriores aparecem nos dois lados, por não haver snapshots globais.
5. Épocas: mês/fuso do aparelho, áreas antigas desconhecidas, repetição de locais sem alterar primeira data, prioridade do último percurso.
6. Cartão: título longo/acentos, simulado/interrompido/parcial, PNG 1080×1440, GIF 360×480, abertura num visualizador, ciclo/cores/legibilidade. Cancelamento do seletor, pouco espaço, rotação e bloqueio de iniciar viagem durante exportação.
7. Backup 1 e 2 em arquivo real, datas novas preservadas, duplicatas, corrupção e limites, sem apagar exploração anterior.
8. Regressão do serviço: tela bloqueada, notificações, pausa, falta de internet/GPS/permissões e recuperação após morte do processo. Não há funcionamento prometido após parada forçada.

## Limites de produto

Somente o último rastro por modo, até 20 mil amostras. Datas e tempos anteriores à 0.5.0 não são inventados. Antes/depois usa as células novas da última viagem (disponíveis desde 0.4.0); sem esses dados, as metades podem ficar iguais. Importações posteriores são comuns às duas metades. Épocas/replay/comparação não gravam exploração.

A névoa animada usa até 20 mil células no viewport e tempos conhecidos. Acima disso ou sem tempos, a linha continua em replay com a névoa final. Abaixo de zoom 12 prevalece a cobertura aproximada. A névoa detalhada continua limitada a 60 mil células; mapa suportado até ±85°. Recorte de comparação atravessando mais de 180° pode ser omitido.

Replay no app dura cerca de 12 s. GIF é ilustração animada do cartão, 40 quadros e 16 cores, cerca de 11 s por ciclo; não é vídeo/captura do mapa-base ou animação dos tiles. PNG e GIF são gerados localmente, sem envio automático e sem nova permissão de armazenamento. Dados de trajeto nos arquivos ficam no destino escolhido.

Backup 2 inclui primeira data por célula; restauração aceita formato 1 com datas desconhecidas. Ambos os limites de 128 MB e 5 milhões de células são aplicados. Não inclui rastro, tempos relativos da viagem, simulação, preferências ou criptografia.

Curitiba é traçado de demonstração com coordenadas de ruas OpenStreetMap, atribuído no app/documentação/cartão simulado. Não valida sentidos viários nem orienta direção. MapLibre/MapTiler ainda dependem de teste de carregamento no celular; sem mapas offline completos.
