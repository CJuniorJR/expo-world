# Expo World — MVP Android

Aplicativo nativo em Kotlin/Jetpack Compose que revela uma faixa ao redor do caminho percorrido. MapLibre Native exibe o mapa; Room preserva a exploração e o rastro por velocidade do último percurso. Interface em português do Brasil. Sem login, backend, sincronização, anúncios ou navegação. Trajetos reais podem ser salvos localmente com título e consultados depois.

## Abrir e configurar

1. Abra esta pasta no Android Studio. Selecione **JDK 17** para o Gradle (Settings → Build, Execution, Deployment → Build Tools → Gradle). Não use Java 8.
2. Instale pelo SDK Manager **Android SDK Platform 36**, **Build Tools 35.0.0** e **Platform Tools**. O mínimo para executar é Android 6/API 23. O app tem `targetSdk = 36`.
3. Copie `local.properties.example` para `local.properties`, ajuste `sdk.dir` e forneça uma chave de desenvolvimento do [MapTiler Cloud](https://cloud.maptiler.com/). Não versione este arquivo.
4. Aguarde o Gradle Sync, selecione o módulo `app` e execute em aparelho/emulador.

Sem chave/URL de estilo, há um fundo neutro com orientação clara. Ele permite testar a névoa e a simulação, mas **não contém ruas**. Com a chave, o estilo `streets-v4` carrega ruas e referências. Se o mapa falhar, a interface permite tentar novamente; o GPS e o banco local continuam independentes do carregamento dos tiles.

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
.\gradlew.bat :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
python scripts/check_migration_sql.py
```

O APK, quando compilado, fica em `app/build/outputs/apk/debug/app-debug.apk`. A entrega atual também está em `dist/expo-world-0.3.2-debug.apk`. O teste conectado exige um Android conectado por ADB ou emulador iniciado. A verificação Python usa a biblioteca padrão para testar o SQL das migrações em SQLite do computador, sem substituir o teste Room no Android. Relatórios: `core/build/reports/tests/test/index.html` e `app/build/reports/lint-results-debug.html`.

`scripts/bootstrap-build.ps1` pode baixar JDK 17 em `.tools` e executar a compilação, sem alterar a instalação de Java do sistema. `-InstallAndroidSdk` também instala ferramentas Android locais e apresenta as licenças do SDK para revisão/aceitação. O script precisa de acesso à internet; downloads e caches não são versionados. Se já tiver SDK instalado, defina `ANDROID_HOME` ou `sdk.dir`. A primeira execução baixa o Gradle e as dependências.

## Uso em aparelho

Na versão 0.2.0 o mapa ocupa a tela inteira, com controles flutuantes e névoa mais suave. O botão de menu (três linhas, no alto à direita) abre um painel com progresso, resumo do percurso, estado do GPS, rastro por velocidade, permissões e modo simulado. Toque fora do painel, deslize-o para baixo ou use o botão de fechar para voltar ao mapa. O botão com uma mira centraliza sua posição; iniciar/encerrar continua acessível sobre o mapa. A atribuição permanece visível com o menu fechado.

Ao iniciar, o mapa acompanha a posição com transições de câmera. Mover ou girar o mapa com os dedos pausa esse acompanhamento; a mira retoma-o. O menu oferece **Acompanhar minha posição**. Isso só movimenta a câmera: não fornece navegação nem instruções de direção.

O aviso **Sem internet** distingue a conexão do sinal GPS. O registro local continua, e o app tenta recarregar o estilo após a conexão voltar caso seu carregamento tenha falhado. A leitura do estado de rede usa uma permissão normal (`ACCESS_NETWORK_STATE`), sem novo diálogo de permissão e sem enviar dados da exploração.

## Temas

Abra o menu e escolha **Temas**: **Verdinho (padrão original)**, **Azul**, **Vermelho**, **Rosa**, **Amarelo**, **Branco** ou **Preto**. Cada tema combina interface, Palio, pedestre/bicicleta, destaque dos bairros e degradê do rastro. Branco usa interface clara; os demais usam fundos escuros com a cor escolhida. Não há seletor de cores individuais.

A escolha muda imediatamente e fica salva nas preferências locais, inclusive após fechar/reabrir. Sem preferência válida, o app mantém o verdinho original, incluindo pintura e degradê anteriores. O tema também se aplica à visualização dos trajetos salvos, sem alterar suas amostras, quilômetros ou modalidade. Paradas continuam pretas, sem bolinhas; velocidade desconhecida continua cinza. Não altera a base de mapas do provedor nem separa o progresso. Nenhuma migração ou limpeza do banco é necessária.

## Progresso e resumo

### Curitiba: bairros e modalidades

O menu permite escolher **Carro**, **Caminhada** ou **Bicicleta** antes de iniciar. Durante o registro a atividade fica bloqueada. Cada percurso e sua cópia salva guardam a modalidade; caminhadas mostram ritmo médio em min/km, bicicletas e carros em km/h. As escalas de cores, limites de plausibilidade e detecção de parada são adequados à atividade. O marcador é um pedestre ou bicicleta em pixel art nesses modos; em carro continua o Palio verde, apontando para o rumo do deslocamento.

Logo abaixo do seletor de atividade, o painel **Curitiba • ruas exploradas** mostra o percentual geral e uma prévia de bairros, mesmo antes de obter GPS. Ele identifica o bairro da posição atual e apresenta quilômetros percorridos únicos, total estimado de vias elegíveis e percentual por bairro. **Ver os 75 bairros** abre a lista com busca; tocar num bairro enquadra seus limites e o destaca no mapa. Fora de Curitiba o GPS, a névoa e os trajetos continuam funcionando, mas esta base não concede progresso de ruas.

Os limites vêm da [Malha Censitária IBGE 2022](https://www.ibge.gov.br/geociencias/organizacao-do-territorio/malhas-territoriais/26565-malhasde-setores-censitarios-divisoes-intramunicipais.html), município 4106902, referência territorial 31/07/2022. O [diretório oficial](https://geoftp.ibge.gov.br/organizacao_do_territorio/malhas_territoriais/malhas_de_setores_censitarios__divisoes_intramunicipais/censo_2022/bairros/shp/UF/) disponibiliza os arquivos como públicos. Há limites mais recentes no IPPUC, mas não afirmamos que este recorte censitário representa todas as mudanças legais posteriores.

As ruas são uma extração OpenStreetMap de **15/10/2026, 19:38:21 UTC**, distribuída sob [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/) com atribuição visível e acesso à licença no menu. O pacote é offline, com 43.655 partes de vias. Não consultamos Overpass durante o uso nem enviamos GPS. [Fontes, regras, reprodução e limitações](data/curitiba/README.md); [tabela com quilômetros por bairro e modalidade](data/curitiba/totals.json).

As estimativas de elegibilidade de cada modalidade são **5.831,264 km para carro**, **6.084,683 km para caminhada** e **6.106,774 km para bicicleta**. Medimos eixos geográficos, sem altitude, com uma projeção local centrada em Curitiba. Não são valores exatos de levantamento: há vias ausentes, classificações incompletas e pistas separadas que contam como eixos distintos. Calçadas e travessias explicitamente identificadas são excluídas para reduzir duplicação com a rua. Caminhos e ciclovias podem entrar no total de modalidades compatíveis; acessos restritos explícitos e obras não entram. Esses dados não certificam segurança, acessibilidade ou permissão atual de passagem e não fornecem navegação.

Para reconhecer ruas, usamos uma tolerância máxima de 12 m, independente dos 50 m da névoa. Ambos os pontos precisam de precisão até 12 m, continuidade até 12 s, deslocamento de 3 a 100 m e velocidade plausível. Passos curtos de caminhada são acumulados nessa janela. A correspondência busca a via mais próxima, verifica alinhamento e rejeita aproximações ambíguas a vias paralelas. Não conectamos lacunas, saltos nem posições paradas com velocidade nativa próxima de zero.

Cada via é dividida em partes de até 10 m. A região central de uma parte precisa ser alcançada para receber crédito; uma chave única impede somar repetições ou ida/volta. O percentual é `metros únicos reconhecidos / metros elegíveis do bairro`, limitado a 100%. Nas divisas, cada parte tem um único bairro dono, pela ordem do código IBGE, para não duplicar o total. A resolução pode omitir ou exceder cerca de uma parte nas extremidades; curvas, ruas paralelas e GPS ruim podem causar omissões ou atribuições incorretas. Não há acurácia percentual garantida.

O mapa, a névoa e o percentual por bairro são **compartilhados entre carro, caminhada e bicicleta**. O denominador único é a união das vias elegíveis, estimada em **6.331,757 km**; uma rua é contada uma vez, mesmo que percorrida em modalidades diferentes. Real/simulado e versões da base continuam separados. A consulta reúne também os créditos já gravados por atividade, sem apagar dados nem exigir nova migração. A migração **4→5** preserva células, rastros e títulos; percursos anteriores recebem atividade **Carro**. O progresso de ruas começa com novos registros: a névoa antiga não permite recuperar com confiança quais ruas foram percorridas. Não há incentivo, ranking ou recompensa por velocidade. Salvar/reabrir um trajeto não concede crédito novamente.

Arquivos distribuídos: `app/src/main/assets/curitiba-network.json` e `curitiba-neighborhoods.geojson`. O catálogo é JSON UTF-8, comprimido pelo ZIP do APK: arquivos de entrada `.gz` são expandidos e renomeados pelo AAPT, o que causou a indisponibilidade na versão 0.3.0. `check_apk_assets.py` verifica os nomes e bytes no APK final. Atualizações futuras da base precisam de remapeamento explícito; o namespace impede misturar numeradores antigos com novos totais.

Verificações específicas:

```powershell
python scripts/check_curitiba_data.py
python scripts/check_apk_assets.py
python scripts/check_migration_sql.py
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
```

Em aparelho, confira uma caminhada real lenta, uma pedalada, repetição do mesmo quarteirão, divisas, retorno sem internet e atualização sem desinstalar. Registros com precisão entre 12 e 20 m ainda podem revelar a névoa, mas não ganham crédito de ruas; essa diferença é intencional.

O painel mostra uma estimativa da **área descoberta** em m²/km², calculada a partir das células únicas com correção da escala por latitude. Repetir o mesmo lugar não soma novamente essa área. Marcos locais — Primeiras descobertas, Explorador do bairro e Novos horizontes — dependem da área revelada, sem recompensas por velocidade ou prazos para dirigir. Real e simulado têm progresso separado.

O resumo do rastro mostra distância registrada, tempo entre registros, duração aproximada parado e ritmo médio GPS. A distância ignora lacunas e deriva entre leituras do mesmo grupo de parada. A média usa as velocidades conhecidas ponderadas pelo tempo; velocidade desconhecida não vira zero. A aquisição inicial do GPS não faz parte do tempo entre registros. Se o limite de amostras foi ultrapassado, o resumo é marcado como parcial. São estimativas para explorar o mapa, não um odômetro ou medição precisa de trânsito.

## Rastro por velocidade

Inicie um **novo** percurso: a linha aparece **ao vivo**, assim que chegar uma leitura válida, e cresce com o trajeto. No tema padrão, é preto quando parado (velocidade estimada de até o limiar da atividade), verde-azulado escuro nos trechos lentos, progressivamente mais claro até verde-água muito claro a 60 km/h ou mais. Uma velocidade constante mantém a mesma tonalidade; desacelerar escurece o rastro. Trechos sem velocidade confiável ficam cinza. Ao tocar em **Encerrar exploração**, a última amostra é salva e o mapa tenta enquadrar o resultado, respeitando os controles flutuantes.

Não há círculos de parada. O rastro fica preto nos trechos parados; quando as coordenadas não mudam, o último segmento de chegada fica preto, sem criar geometria artificial nem unir lacunas. Grupos de leituras a até o limiar da atividade durante pelo menos 5 segundos continuam entrando no resumo. No menu, **Rastro por velocidade** exibe a legenda, número de paradas e duração aproximada acumulada. **Exibir percurso** permite esconder a linha; **Enquadrar percurso** volta à visão do rastro. Velocidade baixa pode representar trânsito, semáforo ou outro motivo: não consultamos dados de trânsito nem inferimos sua causa.

O último percurso encerrado fica salvo no aparelho e reaparece ao reabrir. No modo real, começar outro só substitui o rastro anterior quando o primeiro ponto válido é gravado; iniciar/encerrar sem sinal preserva o resultado anterior. A troca e a primeira gravação são atômicas. A exploração acumulada é preservada em todos os casos. Real e simulado continuam separados. No menu, **Salvar trajeto com título** guarda uma cópia independente do último percurso real concluído. Informe um título de 1 a 80 caracteres; a seção **Trajetos salvos** permite selecionar e enquadrar essas cópias no mapa, com resumo e cores. **Voltar ao último percurso** retorna ao registro mais recente. Salve antes de iniciar outro percurso; simulações e viagens em andamento não podem ser salvas. Os trajetos nomeados sobrevivem à substituição do último percurso, reinício do app e limpeza da simulação. Percursos anteriores à versão 0.1.3 não têm dados de velocidade e não podem receber o rastro retroativamente.

A velocidade nativa do GPS é preferida, desde que presente, plausível e com incerteza de velocidade até 3 m/s quando o Android a fornece. Sem ela, estimamos deslocamento em metros numa janela de 4–6 segundos somente quando a precisão posicional é de até 10 m. Caso contrário a velocidade fica desconhecida. A suavização exponencial de 3 segundos reduz oscilações, mas introduz um pequeno atraso ao desacelerar; tempos de parada são estimativas, não medições exatas.

Somente leituras aceitas pelo filtro de GPS alimentam o rastro. Lacunas, saltos e travessias do antimeridiano quebram os segmentos e reiniciam a análise de parada. A gravação é incremental junto com as células reveladas, numa transação Room; o encerramento também grava a última amostra pendente antes de publicar o resultado.

O rastro é persistido a cada 5 segundos ou 35 metros, e mais frequentemente ao mudar significativamente de velocidade, entrar/sair de parada ou iniciar outro segmento. O ponto atual válido também estende a ponta ao vivo entre gravações, respeitando as rupturas de sinal. Mantemos até **20 mil amostras no último percurso de cada modo**, descartando as mais antigas se ultrapassar esse limite. A geometria e os resumos são montados em dispatchers de trabalho. O banco usa snapshots transacionais para não misturar pontos de viagens diferentes. Parada forçada/morte abrupta do processo pode deixar um percurso incompleto, que não é publicado como encerrado; não há recuperação automática desse rastro.

Teste sem dirigir: abra o menu, ative **Trajeto simulado** e aguarde cerca de 45 segundos (mais o tempo de processamento do aparelho). A reprodução de carro é 8×; caminhada e bicicleta usam tempo virtual e velocidades adequados ao modo, com acelerações, cruzeiro, desacelerações, trechos lentos e paradas. A linha final deve mostrar tonalidades diferentes e trechos pretos, sem marcadores de parada. O resumo usa o tempo virtual do trajeto, e não o tempo de espera na tela. Feche e reabra, ative o modo simulado e confira o mesmo rastro; iniciar outra simulação substitui-o.

Abra o app, toque em **Permitir localização** e escolha **Precisa / Durante o uso**. Isso mostra a posição sem gravar exploração. **Centralizar** retorna à posição atual; mover, girar e ampliar usa a projeção nativa do MapLibre. A névoa é uma camada geográfica, sem máscara fixa em pixels.

Com o GPS ligado, toque em **Iniciar exploração** antes de dirigir. Uma notificação indica a viagem ativa. O serviço `location` iniciado pela atividade visível continua com tela bloqueada e em segundo plano nas condições suportadas pelo Android. **Encerrar exploração**, na interface ou notificação, remove as atualizações, drena a fila de gravação e encerra o serviço. O uso na viagem é passivo.

São solicitadas localização precisa/aproximada juntas (exigência do diálogo moderno) e, no Android 13+, notificações para mostrar o controle de encerramento. A localização aproximada é reconhecida, mas não inicia exploração: ela não oferece precisão adequada à faixa. A interface orienta a habilitar precisão nas configurações, incluindo quando a permissão foi negada permanentemente. Não pedimos `ACCESS_BACKGROUND_LOCATION`, inicialização no boot ou exclusão de otimização de bateria. As permissões de serviço de primeiro plano e serviço de localização constam no manifesto.

Negar notificações não bloqueia o serviço: o Android pode exibir apenas o gerenciador de apps ativos. A interface avisa e permite encerrar. GPS desligado antes da viagem gera orientação para configurações; desligá-lo durante a viagem mantém o serviço aguardando sinal. Perda de sinal ou leituras imprecisas não revelam trechos. O botão de início volta a funcionar quando permissões e GPS estão adequados.

O serviço não reinicia automaticamente após morte do processo (`START_NOT_STICKY`). Forçar parada, interromper pelo gerenciador do Android, reiniciar o aparelho ou políticas de bateria do fabricante podem encerrá-lo. Reabra o app e inicie outra viagem. Não há promessa de funcionamento após parada forçada. Exploração já confirmada no banco é preservada; morte abrupta pode perder as últimas leituras ainda na fila.

## Trajeto simulado

Disponível somente no build **debug**. Abra o menu, ative **Trajeto simulado** e toque em **Iniciar trajeto simulado**. Um trajeto de aproximadamente 3,3 km no centro de Curitiba segue uma polilinha de ruas obtida do OSRM/OpenStreetMap. A geometria está incorporada ao app e não exige consulta de roteamento durante o teste. A reprodução usa uma atualização a cada 125 ms (carro em 8×; caminhada/bicicleta ajustam o tempo virtual, cerca de 45 segundos mais processamento), com velocidades variadas, curvas e paradas. O mesmo filtro, rasterização, análise de velocidade e repositório do GPS processam as leituras; o relógio virtual fica restrito à simulação. Dados geográficos © OpenStreetMap contributors, ODbL; geometria consultada em 15/10/2026, sem garantia de refletir alterações posteriores no trânsito. O indicador sobre o mapa e o painel identificam **MODO SIMULADO**.

Não exige permissão de localização. Os dados usam o namespace `simulation` no banco, separado de `real`; cada novo teste limpa somente a simulação. Ela é interrompida quando a tela sai do primeiro plano e não valida o serviço em segundo plano. Após encerrar, o mapa simulado pode ser consultado. A exploração real continua intacta ao voltar ao modo real.

## Estratégia da exploração

`core/Exploration.kt` contém `ExplorationConfig.REVEAL_RADIUS_METERS = 50.0`, fácil de alterar. A grade persistente agora usa **10 metros em Web Mercator**, com índices inteiros, em vez de 20 m. A migração 2→3 subdivide cada célula antiga em quatro, mantendo sua cobertura e preservando os rastros existentes. A distância entre fixações usa fórmula haversine em metros; a cápsula ao redor do segmento corrige o raio por `1 / cos(latitude)`. Não tratamos graus como metros. O cálculo local de segmentos curtos aproxima a Terra esférica, adequado a este MVP.

Uma célula é revelada quando seu centro pertence à cápsula. O erro de discretização caiu para cerca de **7,08 m** no equador, menor em outras latitudes. Na renderização, uma abertura morfológica de 4 m projetados arredonda os cantos de forma conservadora, seguida de antialiasing; isso pode encolher um pouco a borda. Não é um buffer vetorial exato. A área estimada usa as células persistidas, não o pequeno recuo visual. O tamanho da célula integra a versão do banco: alterá-lo exige migração, enquanto mudar o raio afeta apenas novas revelações.

O filtro rejeita precisão pior que **20 m**, fixações inválidas, antigas (>10 s), futuras, repetidas e deslocamentos acima de 55 m/s. Em leituras separadas por até 2 segundos, a velocidade Doppler disponível também ajuda a rejeitar saltos de coordenadas incompatíveis, respeitando margens de precisão e aceleração. Lacunas >15 s, segmentos >500 m ou travessia do antimeridiano iniciam ilhas independentes. Uma fixação imprecisa rompe a continuidade; um salto é descartado e o próximo ponto inicia uma nova ilha. Nenhuma linha é interpolada através de uma grande lacuna. O filtro mais rigoroso pode deixar mais lacunas em locais com sinal ruim; ele não melhora fisicamente o receptor GPS nem substitui map matching. Ajuste com dados reais antes de produção.

Room faz `INSERT OR IGNORE` para as células por lote/fixação em transação. Repetir um lugar não aumenta o número de células. A exploração cresce com a área visitada, não com o tempo nem com um histórico de pontos. A partir da versão 0.1.3 há também armazenamento limitado de amostras do último percurso para o rastro por velocidade. A migração 3→4 adiciona a lista de trajetos salvos sem alterar células ou percursos anteriores. Cada salvamento copia sessão e amostras numa única transação, validando que o percurso real ainda é o mesmo e está concluído. Cada cópia preserva até 20 mil amostras disponíveis, incluindo a indicação de resumo parcial quando aplicável. Backup do Android está desativado para manter os dados locais.

Para renderizar, consultamos apenas células dentro do retângulo visível por índice `(mode,y,x)`. Comprimimos células adjacentes em faixas, fazemos união topológica com JTS e subtraímos do polígono mundial. Isso evita buracos sobrepostos inválidos. Cálculos e consultas ficam em dispatchers de trabalho; resultados antigos são cancelados ao mudar câmera/modo. A camada de névoa acompanha zoom, rotação e deslocamento do MapLibre.

O zoom mínimo é **8**, máximo 20, para permitir uma visão mais ampla do rastro, e cada geometria de névoa é limitada a **60 mil células**. Ao exceder o limite, o app mantém a névoa conservadora e pede aproximação; o rastro continua visível acima dela. Essa escolha evita construir uma união mundial enorme. A exploração é suportada entre latitudes -85° e +85°. Nas bordas do antimeridiano, segmentos são separados; esse caso merece validação visual em aparelho. Não há compactação avançada de territórios continentais neste MVP.

## Mapa, dados e provedor

- **MapLibre Native** é o renderizador, não um provedor de mapas.
- **OpenStreetMap** fornece dados geográficos sob ODbL; a atribuição dos colaboradores é obrigatória.
- **MapTiler Cloud** fornece o estilo, tiles, sprites e fontes selecionados aqui. Use uma chave própria e um plano adequado a desenvolvimento. Cotas, uso comercial e condições variam conforme o plano: confira o [painel/planos](https://www.maptiler.com/cloud/) e os [termos oficiais](https://www.maptiler.com/terms/cloud/) antes de publicar.

A interface mantém `© MapTiler • © OpenStreetMap contributors`, além do controle de atribuição do MapLibre. [Atribuição OSM e licença](https://www.openstreetmap.org/copyright). Não usamos servidores públicos OSM ou demo tiles como infraestrutura irrestrita de produção. `MAP_STYLE_URL` permite trocar o estilo MapTiler; para outro provedor, revise também atribuição, licença e uso permitido.

Chaves de tiles distribuídas num APK são recuperáveis; `local.properties` evita incluí-las no código-fonte, mas não as transforma em segredo no cliente. Use chave dedicada, acompanhe consumo e aplique as restrições disponíveis para sua integração conforme [documentação de chaves MapTiler](https://docs.maptiler.com/guides/credentials/api-key/). Não fornecemos chave compartilhada.

Internet é necessária para obter tiles/estilo ainda não carregados. Cache eventual do SDK não constitui suporte a mapas offline completos. Nenhuma operação de GPS ou persistência depende do provedor ou da internet; mapas não carregados podem ficar sem ruas, enquanto a exploração continua sendo salva localmente.

## Fluxo de alterações no GitHub

Antes de começar, busque as atualizações remotas e confira a `main`, as branches e os PRs existentes para incluir as alterações feitas em outros computadores. Trabalhe em uma branch `codex/`. Cada entrega deve ter um **Pull Request com destino à `main`**, com resumo e verificações; integre pela interface/API de PR após as verificações e a autorização aplicável, em vez de deixar a entrega apenas na branch ou fazer push direto na `main`. Ao concluir, informe o link do PR e o estado do merge. Nunca inclua `local.properties`, chaves, bancos pessoais, caches ou APKs nos commits.

## Organização e versões

- `app/.../ui`: mapa e montagem da névoa visível; `MainActivity`: interface Compose e permissões.
- `app/.../location`: serviço, processamento e fonte simulada.
- `app/.../storage`: Room, DAO e repositório.
- `core`: geometria, filtro e testes JVM sem dependência do Android.

Versões fixadas, estáveis; não são necessariamente as mais recentes. Conferidas nas fontes oficiais: [AGP 8.13.2 / Gradle 8.13 / JDK 17](https://developer.android.com/build/releases/agp-8-13-0-release-notes), [Kotlin 2.2.21](https://kotlinlang.org/docs/releases.html), [KSP 2.2.21-2.0.4](https://github.com/google/ksp/releases/tag/2.2.21-2.0.4), [Compose BOM 2025.08.01](https://developer.android.com/develop/ui/compose/bom/bom-mapping), [Room 2.8.5](https://developer.android.com/jetpack/androidx/releases/room), [MapLibre 13.5.3](https://github.com/maplibre/maplibre-native/releases/tag/android-v13.5.3) e [JTS 1.20.0](https://github.com/locationtech/jts/releases/tag/1.20.0). Activity 1.10.1, Lifecycle 2.9.4 e Core 1.17.0 constam das respectivas notas AndroidX. Compatibilidade efetiva depende também da compilação descrita em `VALIDATION.md`.

Regras consultadas: [serviço de localização e pré-requisitos](https://developer.android.com/develop/background-work/services/fgs/service-types#location), [iniciar serviço com atividade visível](https://developer.android.com/develop/background-work/services/fgs/launch), [notificações no Android 13+](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

## Verificação em aparelho

Execute o teste Room conectado e, em ambiente seguro, faça estes testes manualmente (passageiro ou trajeto a pé; não interaja dirigindo):

1. Instalação limpa: mapa escurecido, permissão com explicação, marcador após fixação precisa.
2. Iniciar viagem: percorrer trecho e conferir faixa contínua; mover, girar, ampliar e centralizar.
3. Bloquear tela por 5–10 minutos e voltar: verificar áreas novas. Repetir em segundo plano e nos Android 13, 14, 15 e 16/fabricantes pretendidos.
4. Encerrar pela interface e pela notificação: deslocamentos posteriores não acrescentam células; notificação removida.
5. Fechar/reabrir: áreas reveladas reaparecem após carregar mapa, sem iniciar viagem automaticamente.
6. Desligar internet mantendo GPS: células continuam aumentando; restaurar internet e carregar mapa novamente.
7. Negar localização/notificações; conceder apenas aproximada; desligar/ligar GPS antes e durante viagem; entrar em túnel: orientações claras, sem conectar grandes lacunas.
8. Girar aparelho e alterar permissões durante viagem. Forçar parada: serviço deve parar; os dados confirmados devem sobreviver.

Veja `VALIDATION.md` para o resultado efetivamente executado nesta entrega. Testes existentes não devem ser confundidos com testes já aprovados.

## Verificação dos novos trajetos

- Execute a simulação até o fim: conferir curvas pelas ruas, velocidades variadas, paradas pretas sem bolinhas e tempo virtual no resumo.
- Encerre uma viagem real, abra o menu, salve com título e selecione-a em Trajetos salvos.
- Inicie outra viagem e execute uma simulação; reabra o app e confira que o trajeto nomeado continua com as mesmas amostras e resumo.
- Atualize uma instalação com banco v3 sem desinstalar e confira a preservação da exploração e do último percurso.
