# Expo World — MVP Android

Aplicativo nativo em Kotlin/Jetpack Compose que revela uma faixa ao redor do caminho percorrido. MapLibre Native exibe o mapa; Room preserva a exploração e o rastro por velocidade do último percurso. Interface em português do Brasil. Sem login, backend, sincronização, anúncios, navegação ou lista de histórico de viagens.

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

O APK, quando compilado, fica em `app/build/outputs/apk/debug/app-debug.apk`. A entrega atual também está em `dist/expo-world-0.4.0-debug.apk`. O teste conectado exige um Android conectado por ADB ou emulador iniciado. A verificação Python usa a biblioteca padrão para testar o SQL das migrações em SQLite do computador, sem substituir o teste Room no Android. Relatórios: `core/build/reports/tests/test/index.html` e `app/build/reports/lint-results-debug.html`.

`scripts/bootstrap-build.ps1` pode baixar JDK 17 em `.tools` e executar a compilação, sem alterar a instalação de Java do sistema. `-InstallAndroidSdk` também instala ferramentas Android locais e apresenta as licenças do SDK para revisão/aceitação. O script precisa de acesso à internet; downloads e caches não são versionados. Se já tiver SDK instalado, defina `ANDROID_HOME` ou `sdk.dir`. A primeira execução baixa o Gradle e as dependências.

## Uso em aparelho

Na versão 0.4.0 o mapa ocupa a tela inteira, com controles flutuantes e névoa mais suave. O botão de menu (três linhas, no alto à direita) abre um painel com progresso, resumo do percurso, estado do GPS, rastro por velocidade, permissões e modo simulado. Toque fora do painel, deslize-o para baixo ou use o botão de fechar para voltar ao mapa. O botão com uma mira centraliza sua posição; iniciar/encerrar continua acessível sobre o mapa. A atribuição permanece visível com o menu fechado.

Ao iniciar, o mapa acompanha a posição com transições de câmera. Mover ou girar o mapa com os dedos pausa esse acompanhamento; a mira retoma-o. O menu oferece **Acompanhar minha posição**. Isso só movimenta a câmera: não fornece navegação nem instruções de direção.

O aviso **Sem internet** distingue a conexão do sinal GPS. O registro local continua, e o app tenta recarregar o estilo após a conexão voltar caso seu carregamento tenha falhado. A leitura do estado de rede usa uma permissão normal (`ACCESS_NETWORK_STATE`), sem novo diálogo de permissão e sem enviar dados da exploração.

## Progresso e resumo

O painel mostra uma estimativa da **área descoberta** em m²/km², calculada a partir das células únicas com correção da escala por latitude. Repetir o mesmo lugar não soma novamente essa área. Marcos locais — Primeiras descobertas, Explorador do bairro e Novos horizontes — dependem da área revelada, sem recompensas por velocidade ou prazos para dirigir. Real e simulado têm progresso separado.

O resumo do rastro mostra distância registrada, tempo entre registros, duração aproximada parado e ritmo médio GPS. A distância ignora lacunas e deriva entre leituras do mesmo grupo de parada. A média usa as velocidades conhecidas ponderadas pelo tempo; velocidade desconhecida não vira zero. A aquisição inicial do GPS não faz parte do tempo entre registros. Se o limite de amostras foi ultrapassado, o resumo é marcado como parcial. São estimativas para explorar o mapa, não um odômetro ou medição precisa de trânsito.

## Novidades da versão 0.4.0

O menu reúne as novas funções, mantendo o mapa em tela inteira:

- **Backup e restauração:** Exportar cria um arquivo `.expoworld` com as células da exploração real. Restaurar valida versão, coordenadas, contagem e checksum SHA-256 antes de mesclar o arquivo numa transação; duplicatas não aumentam a área e nada é apagado. Não inclui rastro, simulação ou preferências. Usa o seletor [Storage Access Framework do Android](https://developer.android.com/training/data-storage/shared/documents-files), sem permissões amplas de armazenamento. Os arquivos são legíveis, sem criptografia, e contêm lugares visitados; o destino é escolhido pelo usuário. Não há upload pelo aplicativo. É preciso encerrar a viagem antes de usar os botões. Limites: 128 MB e 5 milhões de células por arquivo. Processamento em lotes de 1024 células, com cópia temporária no cache privado; importação inválida/cancelada não confirma alterações parciais.
- **Visão geral:** Ver todas as descobertas enquadra o território do modo selecionado e desliga o acompanhamento da câmera. Abaixo de zoom 12 ou acima do limite de geometria detalhada, uma camada verde mostra cobertura aproximada, agregada em até 4000 retângulos por parte do viewport. Essa visualização não altera a exploração e pode abranger espaços não visitados entre células do mesmo bloco. A névoa permanece por baixo; aproximar retorna aos limites detalhados. O zoom mínimo agora é 2. Não requer carregar todas as células na memória.
- **Destaque de descobertas:** viagens iniciadas na versão 0.4.0 guardam as células novas do último percurso numa tabela separada. Ao encerrar, elas ficam douradas por dois minutos na visão detalhada; o menu permite reativar ou esconder. O rastro por velocidade mantém suas cores. Viagens anteriores preservam área/rastro, mas não ganham destaque retroativo. A migração 4→5 apenas cria a tabela; começar outro percurso limpa só o destaque anterior daquele modo.
- **Intensidade da névoa:** controle de 20% a 80%, salvo no aparelho. Ele altera apenas a aparência, sem revelar ou apagar células.
- **Resumo ao encerrar:** abre automaticamente após uma viagem com dados, mostrando área nova, distância, tempo entre registros, paradas e presença de trechos sem continuidade. Pode ser reaberto pelo menu. Encerrar sem pontos não abre resumo do percurso anterior. Pausas e falta de sinal aparecem como tempo sem registro, sem inferir sua causa.
- **Pausar e continuar:** no menu e na notificação da viagem real, pausa remove atualizações GPS do serviço, mantém a notificação e salva a ponta pendente. Continuar mantém a mesma viagem e cria um segmento independente; nenhum caminho é revelado entre os pontos antes/depois da pausa, mesmo numa pausa curta. Encerrar também funciona enquanto pausado. O GPS de prévia da atividade fica suspenso durante a viagem. Pausa não persiste como sessão retomável após morte do processo: reabrir mostra o rastro interrompido e permite começar outra viagem.
- **Simulações adversas (debug):** escolha Percurso normal, Túnel sem sinal, GPS oscilando, Salto de posição ou Perda e retorno do sinal. Todos usam o filtro real e dados separados. Também é possível pausar a simulação. Os cenários levam cerca de dois minutos, além de pausas; não validam sensores, notificações ou segundo plano reais.

### Testar as novas funções

1. Execute cada cenário simulado, encerre e confira o resumo, as lacunas e o destaque dourado. O salto não deve gerar uma reta longa. A simulação termina ao sair do primeiro plano.
2. Pause um percurso pelo menu, aguarde e continue: a linha recomeça em outro segmento. Repita a pausa/continuação pela notificação de uma viagem real.
3. Ajuste a névoa, reabra o app e confira o valor. Use Ver todas as descobertas e aproxime/afaste: verde indica a visão aproximada; dourado indica células novas no detalhe.
4. Exporte a exploração real; restaure o mesmo arquivo duas vezes. A segunda importação deve acrescentar zero células. Num outro aparelho, configure a chave do mapa e restaure o arquivo para consultar as descobertas.
5. Selecione arquivo inválido/incompleto: deve aparecer orientação sem apagar dados. Encerre viagens antes de abrir o seletor de arquivos.
6. Em aparelho real, confira bloqueio da tela, pausa, retorno, encerramento pela notificação, falta de internet e recuperação após interrupção. A ausência de um aparelho no ambiente impede validar esses itens aqui; consulte o relatório.

## Recursos da versão 0.3.0

- **Recuperação:** o último rastro incompleto é exibido automaticamente quando não há viagem ativa. O aviso distingue esse caso de um encerramento normal. O app recupera os dados em disco; não retoma o GPS, não conecta viagens diferentes e não promete registrar após parada forçada.
- **Área nova por viagem:** o painel mostra a área de células inseridas pela primeira vez naquela viagem, com correção por latitude. Repetir locais soma zero. O valor é salvo atomicamente com as células, separado por modo, e permanece no percurso recuperado ou encerrado. O total vale para toda a viagem mesmo se o rastro atingir o limite de amostras. Percursos anteriores à versão 0.3.0 mostram área nova indisponível. A migração 3→4 preserva células e rastros.
- **Precisão:** a etiqueta sobre o mapa e o menu mostram GPS bom (até 10 m), razoável (até 20 m), impreciso (acima de 20 m) ou sem sinal recente (mais de 10 s). Um círculo geográfico azul representa a incerteza reportada pelo sensor; desaparece quando a leitura fica antiga. É uma estimativa, não uma garantia de posição dentro do círculo. O indicador usa também leituras rejeitadas, sem revelá-las. A simulação não apresenta esse diagnóstico como GPS real.
- **Rastro otimizado:** há fontes separadas para histórico consolidado, até 256 pontos recentes e no máximo dois pontos da ponta ao vivo. O histórico é reconstruído quando um bloco fecha ou a retenção remove pontos antigos; a ponta não reconstrói o trajeto inteiro. Rupturas de sinal são preservadas em todos os blocos. Ainda há leitura de snapshots Room e cálculo de paradas; ganhos reais de bateria e fluidez precisam de medição em aparelho.

Para testar, faça uma viagem curta, encerre e consulte a área nova no menu. Repita o mesmo local: a segunda viagem deverá revelar pouca ou nenhuma área adicional, conforme a incerteza do GPS. A simulação limpa suas próprias descobertas em cada execução e não serve para testar repetição. Para recuperação, durante uma viagem real com pontos já salvos, force a parada pelo Android e reabra: o rastro deve aparecer como interrompido, sem notificação de gravação ativa. O registro não continua durante a parada forçada.

## Rastro por velocidade

Inicie um **novo** percurso: a linha aparece **ao vivo**, assim que chegar uma leitura válida, e cresce com o trajeto. É verde-azulado escuro nos trechos lentos/parados, progressivamente mais claro até verde-água muito claro a 60 km/h ou mais. Uma velocidade constante mantém a mesma tonalidade; desacelerar escurece o rastro. Trechos sem velocidade confiável ficam cinza. Ao tocar em **Encerrar exploração**, a última amostra é salva e o mapa tenta enquadrar o resultado, respeitando os controles flutuantes.

Círculos escuros com borda clara marcam grupos de leituras a até 3 km/h durante pelo menos 5 segundos. No menu, **Rastro por velocidade** exibe a legenda, número de paradas e duração aproximada acumulada. **Exibir último percurso** permite esconder a linha; **Enquadrar percurso** volta à visão do rastro. Velocidade baixa pode representar trânsito, semáforo ou outro motivo: não consultamos dados de trânsito nem inferimos sua causa.

O último percurso encerrado fica salvo no aparelho e reaparece ao reabrir. No modo real, começar outro só substitui o rastro anterior quando o primeiro ponto válido é gravado; iniciar/encerrar sem sinal preserva o resultado anterior. A troca e a primeira gravação são atômicas. A exploração acumulada é preservada em todos os casos. Real e simulado continuam separados. Não existe lista de viagens anteriores. Percursos anteriores à versão 0.1.3 não têm dados de velocidade e não podem receber o rastro retroativamente.

A velocidade nativa do GPS é preferida, desde que presente, plausível e com incerteza de velocidade até 3 m/s quando o Android a fornece. Sem ela, estimamos deslocamento em metros numa janela de 4–6 segundos somente quando a precisão posicional é de até 10 m. Caso contrário a velocidade fica desconhecida. A suavização exponencial de 3 segundos reduz oscilações, mas introduz um pequeno atraso ao desacelerar; tempos de parada são estimativas, não medições exatas.

Somente leituras aceitas pelo filtro de GPS alimentam o rastro. Lacunas, saltos e travessias do antimeridiano quebram os segmentos e reiniciam a análise de parada. A gravação é incremental junto com as células reveladas, numa transação Room; o encerramento também grava a última amostra pendente antes de publicar o resultado.

O rastro é persistido a cada 5 segundos ou 35 metros, e mais frequentemente ao mudar significativamente de velocidade, entrar/sair de parada ou iniciar outro segmento. O ponto atual válido também estende a ponta ao vivo entre gravações, respeitando as rupturas de sinal. Mantemos até **20 mil amostras por modo**, descartando as mais antigas se ultrapassar esse limite. A geometria e os resumos são montados em dispatchers de trabalho. O banco usa snapshots transacionais para não misturar pontos de viagens diferentes. Parada forçada/morte abrupta do processo pode deixar um percurso incompleto. Ao reabrir, o último trecho persistido reaparece como **Percurso interrompido • recuperado**, sem reiniciar o serviço. Leituras que ainda estavam na fila ou na ponta ao vivo podem ser perdidas.

Teste sem dirigir: abra o menu, ative **Trajeto simulado**, execute por cerca de dois minutos e encerre/aguarde o fim. A simulação agora inclui velocidade de cruzeiro, desaceleração, trecho lento, uma parada e aceleração. A linha final deve mostrar tonalidades diferentes e um marcador de parada. Feche e reabra, ative o modo simulado e confira o mesmo rastro; iniciar outra simulação substitui-o.

Abra o app, toque em **Permitir localização** e escolha **Precisa / Durante o uso**. Isso mostra a posição sem gravar exploração. **Centralizar** retorna à posição atual; mover, girar e ampliar usa a projeção nativa do MapLibre. A névoa é uma camada geográfica, sem máscara fixa em pixels.

Com o GPS ligado, toque em **Iniciar exploração** antes de dirigir. Uma notificação indica a viagem ativa. O serviço `location` iniciado pela atividade visível continua com tela bloqueada e em segundo plano nas condições suportadas pelo Android. **Encerrar exploração**, na interface ou notificação, remove as atualizações, drena a fila de gravação e encerra o serviço. O uso na viagem é passivo.

São solicitadas localização precisa/aproximada juntas (exigência do diálogo moderno) e, no Android 13+, notificações para mostrar o controle de encerramento. A localização aproximada é reconhecida, mas não inicia exploração: ela não oferece precisão adequada à faixa. A interface orienta a habilitar precisão nas configurações, incluindo quando a permissão foi negada permanentemente. Não pedimos `ACCESS_BACKGROUND_LOCATION`, inicialização no boot ou exclusão de otimização de bateria. As permissões de serviço de primeiro plano e serviço de localização constam no manifesto.

Negar notificações não bloqueia o serviço: o Android pode exibir apenas o gerenciador de apps ativos. A interface avisa e permite encerrar. GPS desligado antes da viagem gera orientação para configurações; desligá-lo durante a viagem mantém o serviço aguardando sinal. Perda de sinal ou leituras imprecisas não revelam trechos. O botão de início volta a funcionar quando permissões e GPS estão adequados.

O serviço não reinicia automaticamente após morte do processo (`START_NOT_STICKY`). Forçar parada, interromper pelo gerenciador do Android, reiniciar o aparelho ou políticas de bateria do fabricante podem encerrá-lo. Reabra o app e inicie outra viagem. Não há promessa de funcionamento após parada forçada. Exploração já confirmada no banco é preservada; morte abrupta pode perder as últimas leituras ainda na fila.

## Trajeto simulado

Disponível somente no build **debug**. Abra o menu, ative **Trajeto simulado** e toque em **Iniciar trajeto simulado**. Um trajeto de aproximadamente 1 km em São Paulo dura cerca de dois minutos e usa o mesmo filtro, rasterização, análise de velocidade e repositório do GPS. O indicador sobre o mapa e o painel identificam **MODO SIMULADO**.

Não exige permissão de localização. Os dados usam o namespace `simulation` no banco, separado de `real`; cada novo teste limpa somente a simulação. Ela é interrompida quando a tela sai do primeiro plano e não valida o serviço em segundo plano. Após encerrar, o mapa simulado pode ser consultado. A exploração real continua intacta ao voltar ao modo real.

## Estratégia da exploração

`core/Exploration.kt` contém `ExplorationConfig.REVEAL_RADIUS_METERS = 50.0`, fácil de alterar. A grade persistente agora usa **10 metros em Web Mercator**, com índices inteiros, em vez de 20 m. A migração 2→3 subdivide cada célula antiga em quatro, mantendo sua cobertura e preservando os rastros existentes. A distância entre fixações usa fórmula haversine em metros; a cápsula ao redor do segmento corrige o raio por `1 / cos(latitude)`. Não tratamos graus como metros. O cálculo local de segmentos curtos aproxima a Terra esférica, adequado a este MVP.

Uma célula é revelada quando seu centro pertence à cápsula. O erro de discretização caiu para cerca de **7,08 m** no equador, menor em outras latitudes. Na renderização, uma abertura morfológica de 4 m projetados arredonda os cantos de forma conservadora, seguida de antialiasing; isso pode encolher um pouco a borda. Não é um buffer vetorial exato. A área estimada usa as células persistidas, não o pequeno recuo visual. O tamanho da célula integra a versão do banco: alterá-lo exige migração, enquanto mudar o raio afeta apenas novas revelações.

O filtro rejeita precisão pior que **20 m**, fixações inválidas, antigas (>10 s), futuras, repetidas e deslocamentos acima de 55 m/s. Em leituras separadas por até 2 segundos, a velocidade Doppler disponível também ajuda a rejeitar saltos de coordenadas incompatíveis, respeitando margens de precisão e aceleração. Lacunas >15 s, segmentos >500 m ou travessia do antimeridiano iniciam ilhas independentes. Uma fixação imprecisa rompe a continuidade; um salto é descartado e o próximo ponto inicia uma nova ilha. Nenhuma linha é interpolada através de uma grande lacuna. O filtro mais rigoroso pode deixar mais lacunas em locais com sinal ruim; ele não melhora fisicamente o receptor GPS nem substitui map matching. Ajuste com dados reais antes de produção.

Room faz `INSERT OR IGNORE` para as células por lote/fixação em transação. Repetir um lugar não aumenta o número de células. A exploração cresce com a área visitada, não com o tempo nem com um histórico de pontos. A partir da versão 0.1.3 há também armazenamento limitado de amostras do último percurso para o rastro por velocidade. Backup do Android está desativado para manter os dados locais.

Para renderizar, consultamos apenas células dentro do retângulo visível por índice `(mode,y,x)`. Comprimimos células adjacentes em faixas, fazemos união topológica com JTS e subtraímos do polígono mundial. Isso evita buracos sobrepostos inválidos. Cálculos e consultas ficam em dispatchers de trabalho; resultados antigos são cancelados ao mudar câmera/modo. A camada de névoa acompanha zoom, rotação e deslocamento do MapLibre.

O zoom mínimo é **2**, máximo 20, para permitir uma visão mais ampla do rastro, e cada geometria de névoa é limitada a **60 mil células**. Em zoom menor que 12 ou ao exceder o limite, o app mantém a névoa e exibe uma cobertura aproximada em verde, com indicação para aproximar; o rastro continua visível acima dela. Essa escolha evita construir uma união mundial enorme. A exploração é suportada entre latitudes -85° e +85°. Nas bordas do antimeridiano, segmentos são separados; esse caso merece validação visual em aparelho. Não há compactação avançada de territórios continentais neste MVP.

## Mapa, dados e provedor

- **MapLibre Native** é o renderizador, não um provedor de mapas.
- **OpenStreetMap** fornece dados geográficos sob ODbL; a atribuição dos colaboradores é obrigatória.
- **MapTiler Cloud** fornece o estilo, tiles, sprites e fontes selecionados aqui. Use uma chave própria e um plano adequado a desenvolvimento. Cotas, uso comercial e condições variam conforme o plano: confira o [painel/planos](https://www.maptiler.com/cloud/) e os [termos oficiais](https://www.maptiler.com/terms/cloud/) antes de publicar.

A interface mantém `© MapTiler • © OpenStreetMap contributors`, além do controle de atribuição do MapLibre. [Atribuição OSM e licença](https://www.openstreetmap.org/copyright). Não usamos servidores públicos OSM ou demo tiles como infraestrutura irrestrita de produção. `MAP_STYLE_URL` permite trocar o estilo MapTiler; para outro provedor, revise também atribuição, licença e uso permitido.

Chaves de tiles distribuídas num APK são recuperáveis; `local.properties` evita incluí-las no código-fonte, mas não as transforma em segredo no cliente. Use chave dedicada, acompanhe consumo e aplique as restrições disponíveis para sua integração conforme [documentação de chaves MapTiler](https://docs.maptiler.com/guides/credentials/api-key/). Não fornecemos chave compartilhada.

Internet é necessária para obter tiles/estilo ainda não carregados. Cache eventual do SDK não constitui suporte a mapas offline completos. Nenhuma operação de GPS ou persistência depende do provedor ou da internet; mapas não carregados podem ficar sem ruas, enquanto a exploração continua sendo salva localmente.

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
