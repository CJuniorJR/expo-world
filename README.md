# Expo World — MVP Android

Aplicativo nativo em Kotlin/Jetpack Compose que revela uma faixa ao redor do caminho percorrido. MapLibre Native exibe o mapa; Room preserva a exploração e o rastro por velocidade do último percurso. Interface em português do Brasil. Sem login, backend próprio, sincronização ou anúncios. Planejamento online de roteiros com orientações em texto, sem navegação por voz. Trajetos reais podem ser salvos localmente com título e consultados depois.

## Rastro consolidado e histórico completo — 0.6.2

A linha do mapa agora mostra a passagem mais recente em cada parte repetida da mesma viagem, inclusive durante o registro. Repetições parciais recortam somente a parte sobreposta; trechos não refeitos permanecem com suas cores. Setas discretas a partir do zoom 14 indicam o sentido mais recente. Temas, Palio, paradas pretas e desbloqueio da névoa continuam funcionando.

O histórico é independente da geometria: cada leitura recebida durante o registro entra no Room em sequência, sem amostragem por tempo/distância, descarte dos primeiros pontos ou sobrescrita por consolidação. Precisão, relógio monotônico e velocidade de entrada ficam separados da velocidade suavizada. Leituras rejeitadas também são preservadas, marcadas como inválidas: não geram distância, linhas nem descobertas. Pausas continuam sem solicitar GPS; retomar quebra a continuidade. Leituras já enfileiradas antes de pausar são drenadas em ordem. Encerrar não duplica o último ponto.

Replay, prévia, PNG e GIF partem do histórico cronológico completo; retornos e voltas não são eliminados. O marcador segue cada passagem enquanto a linha é substituída progressivamente. Buscar um instante anterior descarta somente o cache visual e recompõe o prefixo daquele instante. A migração **8→9** apenas acrescenta metadados; coordenadas e sequências existentes permanecem intactas. Pontos descartados pelas versões antigas não podem ser recuperados.

Para testar sem dirigir: **Menu → Ajustes → Trajeto simulado → Ida e volta • rastro consolidado**. Inicie a simulação, acompanhe a volta, encerre e abra o replay em **Trajetos**. É uma demonstração gráfica em Curitiba, sem indicação de trânsito permitido nas vias. Confira também pausa/retomada e arraste o replay para frente e para trás. [Estratégia, tolerâncias e matriz de validação](docs/trace-consolidation.md).

## Interface e GIF — 0.6.1

O menu tem quatro áreas, com os atalhos sempre visíveis e o conteúdo de cada área rolando separadamente:

- **Explorar**: atividade, planejamento e controles da viagem.
- **Bairros**: percentual de Curitiba, busca dos 75 bairros, área descoberta e visão geral.
- **Trajetos**: resumo, replay, comparação, cartão PNG/GIF e trajetos salvos. Detalhes e cores do rastro ficam em uma seção expansível.
- **Ajustes**: temas, névoa, backup, mapa, localização e simulação.

**Planejar volta** e **Bairros • percentual** também ficam sobre o mapa antes de iniciar. Durante a viagem, o botão principal é **Pausar percurso** ou **Continuar percurso**. **Encerrar percurso** é uma ação separada, menor, e tanto ela quanto o botão do menu pedem confirmação. **Continuar percurso**, voltar ou tocar fora do aviso cancela o encerramento; a gravação mantém seu estado anterior. O aviso não pausa automaticamente. Confirmar encerra o registro e preserva as descobertas; o trajeto real continua podendo ser salvo com título.

O GIF passou de 360×480/40 quadros para **720×960/100 quadros**, com antisserrilhamento, 10 quadros por segundo e pausa final de 1,5 s (11,4 s por ciclo). A paleta global de 256 cores é calculada a partir do cartão completo, incluindo o mapa e as cores do tema, e reutilizada em todos os quadros para evitar oscilações de cor. Preto e cores essenciais têm prioridade. Compressão LZW com dicionário limitado substitui os resets a cada 12 pixels; isso contém o tamanho do arquivo apesar da maior definição. Só um bitmap e buffers de pixels são mantidos para a animação, fora da interface. O formato GIF continua limitado a 256 cores e exportar pode levar mais tempo em celulares modestos. Na versão 0.6.1 o banco não mudou; a atualização 0.6.2 acrescenta os metadados descritos acima.

## Versão unificada 0.5.1 — 16/10/2026

Integra as branches de bairros/temas e replay/exportação na `main`. Preserva backup, visão geral, pausa, primeiras descobertas, comparação, replay, trajetos com título, modalidades e temas. Retorna o percentual por bairro ao menu, remove a camada de bolinhas de parada e inclui o mapa-base na prévia, PNG e GIF. O banco 7 normaliza as estruturas divergentes das versões 4/5 e preserva os dados das duas linhas de desenvolvimento. Nesta entrega o usuário autorizou explicitamente integração direta na `main`, sem novo PR.

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

O APK, quando compilado, fica em `app/build/outputs/apk/debug/app-debug.apk`. A entrega atual também está em `dist/expo-world-0.6.1-debug.apk`. O teste conectado exige um Android conectado por ADB ou emulador iniciado. A verificação Python usa a biblioteca padrão para testar o SQL das migrações em SQLite do computador, sem substituir o teste Room no Android. Relatórios: `core/build/reports/tests/test/index.html` e `app/build/reports/lint-results-debug.html`.

`scripts/bootstrap-build.ps1` pode baixar JDK 17 em `.tools` e executar a compilação, sem alterar a instalação de Java do sistema. `-InstallAndroidSdk` também instala ferramentas Android locais e apresenta as licenças do SDK para revisão/aceitação. O script precisa de acesso à internet; downloads e caches não são versionados. Se já tiver SDK instalado, defina `ANDROID_HOME` ou `sdk.dir`. A primeira execução baixa o Gradle e as dependências.

## Uso em aparelho

Na versão 0.5.1 o mapa ocupa a tela inteira, com controles flutuantes e névoa mais suave. O botão de menu (três linhas, no alto à direita) abre as áreas Explorar, Bairros, Trajetos e Ajustes. Toque fora do painel, deslize-o para baixo ou use o botão de fechar para voltar ao mapa. O botão com uma mira centraliza sua posição; iniciar/encerrar continua acessível sobre o mapa. A atribuição permanece visível com o menu fechado.

Ao iniciar, o mapa acompanha a posição com transições de câmera. Mover ou girar o mapa com os dedos pausa esse acompanhamento; a mira retoma-o. O menu oferece **Acompanhar minha posição**. Isso só movimenta a câmera: não fornece navegação nem instruções de direção.

O aviso **Sem internet** distingue a conexão do sinal GPS. O registro local continua, e o app tenta recarregar o estilo após a conexão voltar caso seu carregamento tenha falhado. A leitura do estado de rede usa uma permissão normal (`ACCESS_NETWORK_STATE`), sem novo diálogo de permissão e sem enviar dados da exploração.

## Exploração de bolso — 0.6.0

Menu → **Explorar** → **Exploração de bolso • planejar uma volta**. Escolha de 10 a 180 minutos (atalhos 15/30/45/60), caminhada/bicicleta/carro e **Explorar** ou **Passear**. A partida pode ser a posição GPS recente ou um ponto tocado no mapa. **Voltar ao início** vem marcado: a ida e a volta entram no orçamento de tempo; desmarcar permite terminar em outro lugar.

**Explorar** compara até três percursos e favorece quilômetros únicos ainda não reconhecidos na base de Curitiba. A escolha de pontos intermediários também favorece ruas com partes ainda não visitadas. O reconhecimento/estimativa ocorre no aparelho; não envia créditos, névoa ou histórico ao roteador. Fora da área coberta, ainda há planejamento, com indicação de que a estimativa de descoberta está indisponível. **Passear** dá mais peso à duração, continuidade, menor repetição e menos mudanças de direção. As opções do roteador reduzem preferência por subidas a pé/bike e por rodovias/pedágios no carro; não garantem percurso plano, agradável, seguro ou sem pedágios.

A duração é uma estimativa do roteador, sem trânsito em tempo real, ajustada com uma quarta consulta quando necessário. Aceitamos opções entre 45% e 110% do tempo informado e mostramos a duração real estimada antes de iniciar; pode não haver solução adequada para uma duração/local. Nenhuma distância em linha reta é apresentada como percurso viário. **Gerar outra opção** varia os pontos sugeridos. Falha/cancelamento conserva o roteiro anterior.

O roteiro aparece como **linha tracejada** acima da névoa, com distância, tempo, estimativa de trechos novos e orientações em texto. **Ver no mapa** enquadra a prévia. **Iniciar este percurso** exige GPS recente e posição a até 150 m da partida, seleciona a atividade correta e usa o serviço de registro existente. A linha efetivamente percorrida continua com cores por velocidade e paradas pretas. Planejar, salvar ou reabrir não concede exploração. **Passear** também revela áreas novas quando de fato percorridas. Não há voz, navegação completa, recálculo automático ou garantia de precisão/atualização das instruções.

Durante o percurso, **Voltar ao início agora** calcula um novo caminho da posição GPS recente para a partida original, usando o perfil da atividade; mantém essa partida mesmo após mais de um recálculo. O caminho anterior é mantido se a consulta falhar. Estando a até 60 m da origem, informa que você já está perto e evita consultar o servidor. O início do retorno não precisa reproduzir o caminho da ida ao contrário.

**Salvar roteiro planejado** grava título, geometria, atividade, objetivo, origem, orçamento, estimativas e orientações no aparelho. A lista é separada de **Trajetos realizados salvos**. Ao reabrir um roteiro, a estimativa de trechos novos é recalculada localmente com os créditos atuais. É possível removê-lo da prévia ou excluir uma cópia salva; isso não apaga a exploração. O banco **7→8** acrescenta somente `planned_routes` e preserva as migrações anteriores. O roteiro atual é recuperado das preferências após reabrir o app; a gravação não reinicia automaticamente. A simulação não usa nem altera roteiros reais.

### Serviço de percursos e privacidade

O usuário autorizou nesta sessão o envio da partida e dos pontos intermediários ao **Valhalla/FOSSGIS** (`https://valhalla1.openstreetmap.de`) para gerar percursos e recalcular retorno. A interface identifica esse envio. Não há envio periódico de GPS. O endpoint é usado somente no **debug para testes pessoais**, com `User-Agent`/`X-Client-Id`, chamadas sequenciais espaçadas no mínimo 1,2 s, resposta limitada a 2 MB e no máximo quatro consultas por geração. Acesso HTTPS, sem seguir redirecionamentos. Dados de exploração continuam locais; mapas e cálculo de novos roteiros precisam de internet.

Para **release/distribuição**, configure `ROUTING_URL` em `local.properties` para uma instância Valhalla própria ou serviço autorizado (base HTTPS, sem `/route`). Release sem configuração bloqueia o planejamento. O servidor público é uma demonstração de uso justo, não infraestrutura irrestrita de produção; seus mantenedores pedem contato antes de publicar aplicativos que o utilizem. [Condições do demo e identificação](https://github.com/valhalla/valhalla#demo-server), [perfis, opções, instruções e formato da API](https://valhalla.github.io/valhalla/api/route/api-reference/). Regras conferidas em 16/10/2026. Não foi enviado pedido aos mantenedores ou publicado aplicativo nesse serviço.

Verificação manual: gere uma caminhada com retorno e uma sem retorno; escolha partida no mapa; compare Explorar/Passear; salve/reabra um roteiro; inicie perto da partida; confira orientações e recálculo para a origem; interrompa a internet e confirme que roteiro anterior e gravação GPS permanecem; atualize sem desinstalar e confira os dados anteriores. O backup de células continua sem incluir roteiros planejados.

## Temas

Abra **Menu → Ajustes → Temas**: **Verdinho (padrão original)**, **Azul**, **Vermelho**, **Rosa**, **Amarelo**, **Branco** ou **Preto**. Cada tema combina interface, Palio, pedestre/bicicleta, destaque dos bairros e degradê do rastro. Branco usa interface clara; os demais usam fundos escuros com a cor escolhida. Não há seletor de cores individuais.

A escolha muda imediatamente e fica salva nas preferências locais, inclusive após fechar/reabrir. Sem preferência válida, o app mantém o verdinho original, incluindo pintura e degradê anteriores. O tema também se aplica à visualização dos trajetos salvos, sem alterar suas amostras, quilômetros ou modalidade. Paradas continuam pretas, sem bolinhas; velocidade desconhecida continua cinza. Não altera a base de mapas do provedor nem separa o progresso. Nenhuma migração ou limpeza do banco é necessária.

## Progresso e resumo

### Curitiba: bairros e modalidades

O menu permite escolher **Carro**, **Caminhada** ou **Bicicleta** antes de iniciar. Durante o registro a atividade fica bloqueada. Cada percurso e sua cópia salva guardam a modalidade; caminhadas mostram ritmo médio em min/km, bicicletas e carros em km/h. As escalas de cores, limites de plausibilidade e detecção de parada são adequados à atividade. O marcador é um pedestre ou bicicleta em pixel art nesses modos; em carro continua o Palio verde, apontando para o rumo do deslocamento.

Em **Menu → Bairros**, o painel **Curitiba • ruas exploradas** mostra o percentual geral e uma prévia de bairros, mesmo antes de obter GPS. Ele identifica o bairro da posição atual e apresenta quilômetros percorridos únicos, total estimado de vias elegíveis e percentual por bairro. **Ver os 75 bairros** abre a lista com busca; tocar num bairro enquadra seus limites e o destaca no mapa. Fora de Curitiba o GPS, a névoa e os trajetos continuam funcionando, mas esta base não concede progresso de ruas.

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

O resumo do rastro mostra distância registrada, tempo entre registros, duração aproximada parado e ritmo médio GPS. A distância ignora lacunas e deriva entre leituras do mesmo grupo de parada. A média usa as velocidades conhecidas ponderadas pelo tempo; velocidade desconhecida não vira zero. O tempo entre leituras inclui os intervalos sem GPS válido. Rastros truncados por versões antigas continuam marcados como parciais; novos registros não têm limite de amostras. São estimativas para explorar o mapa, não um odômetro ou medição precisa de trânsito.

## Novidades da versão 0.5.0

O mapa começa no centro de **Curitiba** (-25.4284, -49.2733). A primeira localização real válida ainda pode centralizar no aparelho. Um rastro antigo não muda automaticamente a cidade ao abrir; use Enquadrar percurso para vê-lo. O enquadramento automático permanece ao encerrar uma nova viagem.

### Reproduzir percurso selecionado

Depois de encerrar, abra Menu → Trajetos → Reproduzir percurso selecionado. Há reprodução/pausa, busca pela barra e Sair. O replay comprime o período registrado em cerca de 12 segundos, mantém as cores de velocidade e um marcador dourado. Não interpola entre segmentos separados por falta de GPS, pausa ou antimeridiano. Ao sair do primeiro plano, a reprodução pausa. Não inicia serviço, não solicita GPS adicional e não grava exploração.

Em viagens iniciadas nesta versão, cada célula nova guarda seu tempo relativo no percurso. No detalhe, a névoa reaparece e se abre na ordem das descobertas. A animação usa um snapshot do viewport e trabalho fora da interface, com no máximo 20 mil células para animar a névoa; em áreas maiores ou viagens sem tempos conhecidos, o replay da linha continua, com a névoa final. Em zoom menor que 12, continua a cobertura aproximada da visão geral. Aproximar permite consultar o detalhe. Rastros antigos já truncados têm replay parcial, começando no primeiro ponto ainda disponível; novas viagens preservam a sequência inteira.

### Antes e depois

Menu → Trajetos → Comparar antes e depois. A barra move uma divisória vertical: à esquerda a névoa cobre novamente as áreas novas da última viagem; à direita aparece o estado atual. Mover/rotacionar o mapa atualiza o recorte geográfico. Os modos replay, comparação e épocas são exclusivos, e começar uma viagem sai deles.

A comparação usa a diferença das células novas do último percurso, disponível a partir da 0.4.0. Ela não altera o banco. Viagens sem áreas novas/sem identificação das células podem mostrar os lados iguais. Não é um histórico de snapshots globais: importações de backup posteriores à viagem aparecem nos dois lados. A comparação detalhada requer zoom/quantidade de células dentro do limite da névoa; junto ao antimeridiano, o recorte pode ser omitido para evitar polígonos inválidos.

### Cartão e animação para salvar

Menu → Trajetos → Criar cartão e exportar animação. Escolha um título de até 40 caracteres e confira a prévia. **Salvar PNG** cria um cartão de 1080×1440, com percurso, data, distância, área nova, tempo e paradas. Percursos simulados, interrompidos ou parciais são identificados. **Salvar GIF** cria uma animação ilustrada do trajeto em 720×960, 100 quadros, 10 quadros/s e paleta adaptativa de 256 cores, com 11,4 segundos por ciclo.

PNG e GIF incluem o mapa-base renderizado pelo MapLibre com o mesmo estilo configurado no aplicativo, além do rastro e da atribuição MapTiler/OpenStreetMap. O enquadramento considera o percurso inteiro, e a projeção do rastro usa o snapshot para manter alinhamento com as ruas. A prévia do cartão usa a mesma imagem e projeção; não é uma captura da tela atual nem inclui seus controles. Internet é necessária para carregar os dados cartográficos ainda não disponíveis. Falha ou tempo limite de 45 segundos mostra orientação para tentar novamente; não é gerado silenciosamente um cartão sem mapa. Os temas são preservados no rastro; paradas são segmentos pretos sem marcadores de parada. Os círculos de início e fim/posição do replay continuam identificando as extremidades.

O seletor Android escolhe o destino, sem nova permissão de armazenamento ou upload automático. Os arquivos contêm o percurso sobre o mapa. A imagem de fundo é renderizada uma vez e reutilizada nos 100 quadros; codificação e buffers ficam fora da interface, com cancelamento e arquivos temporários removidos. A exportação não inclui a animação da névoa: ela continua disponível no replay dentro do mapa. Durante a exportação, iniciar outra viagem fica bloqueado. As prévias JVM em `core/build/preview` verificam layout e desenho vetorial, sem acesso ao mapa real.

### Mapa das primeiras vezes

Menu → Mapa das primeiras vezes. No detalhe, dourado indica as células novas do último percurso; verde, as primeiras descobertas do mês atual; azul, descobertas anteriores ao mês; cinza, data desconhecida. O último percurso tem prioridade de cor. O mês usa o calendário/fuso do aparelho. Datas futuras ou ausentes são tratadas como desconhecidas na visualização. Repassar por um lugar não muda sua primeira data.

O banco unificado **7** recebe migrações das versões 1 a 6. As branches anteriores tinham estruturas diferentes para os mesmos números 4 e 5; a migração verifica as colunas existentes antes de acrescentar o que falta, preservando células, rastros, títulos e créditos. Datas/tempos antigos continuam desconhecidos. Não se inventa quando uma área antiga foi visitada. O backup agora usa formato **2**, com a primeira data por célula; a restauração também aceita formato 1, cujas datas ficam desconhecidas. Duplicatas preservam a data já existente. Limites de 128 MB e 5 milhões de células valem simultaneamente na exportação e restauração. O backup continua sem rastro, tempo relativo do último percurso ou preferências.

### Simulação com curvas em Curitiba

A versão unificada conserva o percurso OSRM/OpenStreetMap de aproximadamente 3,3 km documentado abaixo, com reprodução rápida e velocidades adequadas a carro, caminhada e bicicleta. Os cenários de túnel, oscilação, salto e recuperação de sinal usam a mesma geometria, assim como pausa/continuação. Cada teste limpa somente os dados simulados; a exploração real permanece intacta. A geração é local e não consulta internet. Dados © OpenStreetMap contributors, ODbL. O percurso é uma ferramenta de teste, sem orientação de trânsito. GIF89a segue a [especificação publicada](https://www.w3.org/Graphics/GIF/spec-gif89a.txt).

### Conferência rápida

1. Instale como atualização; confira Curitiba na abertura sem um GPS válido centralizando outra região.
2. Rode uma **nova** simulação normal e encerre: confira curvas, resumo e destaque. Antes/depois deve esconder/repor as áreas novas sem alterar o total explorado.
3. Reproduza e arraste a barra do replay. Repita com Túnel sem sinal ou Pausa: não deve surgir uma linha ligando as lacunas.
4. Abra o mapa das primeiras vezes: as áreas da viagem nova ficam douradas; áreas antigas sem data continuam desconhecidas. Real e simulação não se misturam.
5. Edite o título do cartão, salve PNG/GIF e abra os arquivos num visualizador. Confira acentos, SIMULADO, atribuição, animação e identificação de rastro parcial/interrompido.
6. Exporte/restaure backup novo e antigo: não inventar datas antigas nem alterar a primeira data ao repetir a importação. Cancelar o seletor não deve alterar exploração.

## Recursos da versão 0.4.0

O menu reúne as novas funções, mantendo o mapa em tela inteira:

- **Backup e restauração:** Exportar cria um arquivo `.expoworld` com as células da exploração real. Restaurar valida versão, coordenadas, contagem e checksum SHA-256 antes de mesclar o arquivo numa transação; duplicatas não aumentam a área e nada é apagado. Não inclui rastro, créditos de ruas por bairro, simulação ou preferências. Usa o seletor [Storage Access Framework do Android](https://developer.android.com/training/data-storage/shared/documents-files), sem permissões amplas de armazenamento. Os arquivos são legíveis, sem criptografia, e contêm lugares visitados; o destino é escolhido pelo usuário. Não há upload pelo aplicativo. É preciso encerrar a viagem antes de usar os botões. Limites: 128 MB e 5 milhões de células por arquivo. Processamento em lotes de 1024 células, com cópia temporária no cache privado; importação inválida/cancelada não confirma alterações parciais.
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
- **Área nova por viagem:** o painel mostra a área de células inseridas pela primeira vez naquela viagem, com correção por latitude. Repetir locais soma zero. O valor é salvo atomicamente com as células, separado por modo, e permanece no percurso recuperado ou encerrado. O total vale para toda a viagem e independe da consolidação visual. Percursos anteriores à versão 0.3.0 mostram área nova indisponível. A migração 3→4 preserva células e rastros.
- **Precisão:** a etiqueta sobre o mapa e o menu mostram GPS bom (até 10 m), razoável (até 20 m), impreciso (acima de 20 m) ou sem sinal recente (mais de 10 s). Um círculo geográfico azul representa a incerteza reportada pelo sensor; desaparece quando a leitura fica antiga. É uma estimativa, não uma garantia de posição dentro do círculo. O indicador usa também leituras rejeitadas, sem revelá-las. A simulação não apresenta esse diagnóstico como GPS real.
- **Rastro otimizado:** há fontes separadas para histórico consolidado, até 256 pontos recentes e no máximo dois pontos da ponta ao vivo. O histórico é reconstruído quando um bloco fecha ou a retenção remove pontos antigos; a ponta não reconstrói o trajeto inteiro. Rupturas de sinal são preservadas em todos os blocos. Ainda há leitura de snapshots Room e cálculo de paradas; ganhos reais de bateria e fluidez precisam de medição em aparelho.

Para testar, faça uma viagem curta, encerre e consulte a área nova no menu. Repita o mesmo local: a segunda viagem deverá revelar pouca ou nenhuma área adicional, conforme a incerteza do GPS. A simulação limpa suas próprias descobertas em cada execução e não serve para testar repetição. Para recuperação, durante uma viagem real com pontos já salvos, force a parada pelo Android e reabra: o rastro deve aparecer como interrompido, sem notificação de gravação ativa. O registro não continua durante a parada forçada.

## Rastro por velocidade

Inicie um **novo** percurso: a linha aparece **ao vivo**, assim que chegar uma leitura válida, e cresce com o trajeto. No tema padrão, é preto quando parado (velocidade estimada de até o limiar da atividade), verde-azulado escuro nos trechos lentos, progressivamente mais claro até verde-água muito claro a 60 km/h ou mais. Uma velocidade constante mantém a mesma tonalidade; desacelerar escurece o rastro. Trechos sem velocidade confiável ficam cinza. Ao tocar em **Encerrar exploração**, a última amostra é salva e o mapa tenta enquadrar o resultado, respeitando os controles flutuantes.

Não há círculos de parada. O rastro fica preto nos trechos parados; quando as coordenadas não mudam, o último segmento de chegada fica preto, sem criar geometria artificial nem unir lacunas. Grupos de leituras a até o limiar da atividade durante pelo menos 5 segundos continuam entrando no resumo. No menu, **Rastro por velocidade** exibe a legenda, número de paradas e duração aproximada acumulada. **Exibir percurso** permite esconder a linha; **Enquadrar percurso** volta à visão do rastro. Velocidade baixa pode representar trânsito, semáforo ou outro motivo: não consultamos dados de trânsito nem inferimos sua causa.

O último percurso encerrado fica salvo no aparelho e reaparece ao reabrir. No modo real, começar outro substitui o último rastro quando a primeira leitura é gravada, mesmo que rejeitada e retida somente para auditoria; iniciar/encerrar sem receber leituras preserva o resultado anterior. A troca e a primeira gravação são atômicas. A exploração acumulada é preservada em todos os casos. Real e simulado continuam separados. No menu, **Salvar trajeto com título** guarda uma cópia independente do último percurso real concluído. Informe um título de 1 a 80 caracteres; a seção **Trajetos salvos** permite selecionar e enquadrar essas cópias no mapa, com resumo e cores. **Voltar ao último percurso** retorna ao registro mais recente. Salve antes de iniciar outro percurso; simulações e viagens em andamento não podem ser salvas. Os trajetos nomeados sobrevivem à substituição do último percurso, reinício do app e limpeza da simulação. Percursos anteriores à versão 0.1.3 não têm dados de velocidade e não podem receber o rastro retroativamente.

A velocidade nativa do GPS é preferida, desde que presente, plausível e com incerteza de velocidade até 3 m/s quando o Android a fornece. Sem ela, estimamos deslocamento em metros numa janela de 4–6 segundos somente quando a precisão posicional é de até 10 m. Caso contrário a velocidade fica desconhecida. A suavização exponencial de 3 segundos reduz oscilações, mas introduz um pequeno atraso ao desacelerar; tempos de parada são estimativas, não medições exatas.

Todas as leituras recebidas alimentam o histórico; somente as aceitas pelo filtro de GPS alimentam a geometria visual e a exploração. Lacunas, saltos e travessias do antimeridiano quebram os segmentos e reiniciam a análise de parada. A gravação é incremental junto com as células reveladas, numa transação Room; o encerramento também grava a última amostra pendente antes de publicar o resultado.


Teste sem dirigir: abra o menu, ative **Trajeto simulado** e aguarde cerca de 45 segundos (mais o tempo de processamento do aparelho). A reprodução de carro é 8×; caminhada e bicicleta usam tempo virtual e velocidades adequados ao modo, com acelerações, cruzeiro, desacelerações, trechos lentos e paradas. A linha final deve mostrar tonalidades diferentes e trechos pretos, sem marcadores de parada. O resumo usa o tempo virtual do trajeto, e não o tempo de espera na tela. Feche e reabra, ative o modo simulado e confira o mesmo rastro; iniciar outra simulação substitui-o.

Abra o app, toque em **Permitir localização** e escolha **Precisa / Durante o uso**. Isso mostra a posição sem gravar exploração. **Centralizar** retorna à posição atual; mover, girar e ampliar usa a projeção nativa do MapLibre. A névoa é uma camada geográfica, sem máscara fixa em pixels.

Com o GPS ligado, toque em **Iniciar exploração** antes de dirigir. Uma notificação indica a viagem ativa. O serviço `location` iniciado pela atividade visível continua com tela bloqueada e em segundo plano nas condições suportadas pelo Android. **Encerrar exploração**, na interface ou notificação, remove as atualizações, drena a fila de gravação e encerra o serviço. O uso na viagem é passivo.

São solicitadas localização precisa/aproximada juntas (exigência do diálogo moderno) e, no Android 13+, notificações para mostrar o controle de encerramento. A localização aproximada é reconhecida, mas não inicia exploração: ela não oferece precisão adequada à faixa. A interface orienta a habilitar precisão nas configurações, incluindo quando a permissão foi negada permanentemente. Não pedimos `ACCESS_BACKGROUND_LOCATION`, inicialização no boot ou exclusão de otimização de bateria. As permissões de serviço de primeiro plano e serviço de localização constam no manifesto.

Negar notificações não bloqueia o serviço: o Android pode exibir apenas o gerenciador de apps ativos. A interface avisa e permite encerrar. GPS desligado antes da viagem gera orientação para configurações; desligá-lo durante a viagem mantém o serviço aguardando sinal. Perda de sinal ou leituras imprecisas não revelam trechos. O botão de início volta a funcionar quando permissões e GPS estão adequados.

O serviço não reinicia automaticamente após morte do processo (`START_NOT_STICKY`). Forçar parada, interromper pelo gerenciador do Android, reiniciar o aparelho ou políticas de bateria do fabricante podem encerrá-lo. Reabra o app e inicie outra viagem. Não há promessa de funcionamento após parada forçada. Exploração já confirmada no banco é preservada; morte abrupta pode perder as últimas leituras ainda na fila.

## Trajeto simulado

Disponível somente no build **debug**. Abra o menu, ative **Trajeto simulado** e toque em **Iniciar trajeto simulado**. Um trajeto de aproximadamente 3,3 km no centro de Curitiba segue uma polilinha de ruas obtida do OSRM/OpenStreetMap. A geometria está incorporada ao app e não exige consulta de roteamento durante o teste. A reprodução usa uma atualização a cada 125 ms (carro em 8×; caminhada/bicicleta ajustam o tempo virtual, cerca de 45 segundos mais processamento), com velocidades variadas, curvas e paradas. O mesmo filtro, rasterização, análise de velocidade e repositório do GPS processam as leituras; o relógio virtual fica restrito à simulação. Dados geográficos © OpenStreetMap contributors, ODbL; geometria consultada em 15/10/2026, sem garantia de refletir alterações posteriores no trânsito. O indicador sobre o mapa e o painel identificam **MODO SIMULADO**.
Disponível somente no build **debug**. Abra o menu, ative **Trajeto simulado** e toque em **Iniciar trajeto simulado**. Um trajeto de aproximadamente 1 km no centro de Curitiba dura cerca de dois minutos e usa o mesmo filtro, rasterização, análise de velocidade e repositório do GPS. O indicador sobre o mapa e o painel identificam **MODO SIMULADO**.

Não exige permissão de localização. Os dados usam o namespace `simulation` no banco, separado de `real`; cada novo teste limpa somente a simulação. Ela é interrompida quando a tela sai do primeiro plano e não valida o serviço em segundo plano. Após encerrar, o mapa simulado pode ser consultado. A exploração real continua intacta ao voltar ao modo real.

## Estratégia da exploração

`core/Exploration.kt` contém `ExplorationConfig.REVEAL_RADIUS_METERS = 50.0`, fácil de alterar. A grade persistente agora usa **10 metros em Web Mercator**, com índices inteiros, em vez de 20 m. A migração 2→3 subdivide cada célula antiga em quatro, mantendo sua cobertura e preservando os rastros existentes. A distância entre fixações usa fórmula haversine em metros; a cápsula ao redor do segmento corrige o raio por `1 / cos(latitude)`. Não tratamos graus como metros. O cálculo local de segmentos curtos aproxima a Terra esférica, adequado a este MVP.

Uma célula é revelada quando seu centro pertence à cápsula. O erro de discretização caiu para cerca de **7,08 m** no equador, menor em outras latitudes. Na renderização, uma abertura morfológica de 4 m projetados arredonda os cantos de forma conservadora, seguida de antialiasing; isso pode encolher um pouco a borda. Não é um buffer vetorial exato. A área estimada usa as células persistidas, não o pequeno recuo visual. O tamanho da célula integra a versão do banco: alterá-lo exige migração, enquanto mudar o raio afeta apenas novas revelações.

O filtro rejeita precisão pior que **20 m**, fixações inválidas, antigas (>10 s), futuras, repetidas e deslocamentos acima de 55 m/s. Em leituras separadas por até 2 segundos, a velocidade Doppler disponível também ajuda a rejeitar saltos de coordenadas incompatíveis, respeitando margens de precisão e aceleração. Lacunas >15 s, segmentos >500 m ou travessia do antimeridiano iniciam ilhas independentes. Uma fixação imprecisa rompe a continuidade; um salto é descartado e o próximo ponto inicia uma nova ilha. Nenhuma linha é interpolada através de uma grande lacuna. O filtro mais rigoroso pode deixar mais lacunas em locais com sinal ruim; ele não melhora fisicamente o receptor GPS nem substitui map matching. Ajuste com dados reais antes de produção.

Room faz `INSERT OR IGNORE` para as células por lote/fixação em transação. Repetir um lugar não aumenta o número de células. A exploração cresce com a área visitada, não com o tempo nem com um histórico de pontos. A partir da versão 0.1.3 há também amostras do último percurso para o rastro por velocidade; desde 0.6.2 todas as leituras da viagem são preservadas, sem limite fixo. A migração 3→4 adiciona a lista de trajetos salvos sem alterar células ou percursos anteriores. Cada salvamento copia sessão e amostras numa única transação, validando que o percurso real ainda é o mesmo e está concluído. Cada cópia preserva todas as amostras disponíveis e seus metadados, incluindo a indicação de resumo parcial em rastros antigos já truncados. Backup do Android está desativado para manter os dados locais.

Para renderizar, consultamos apenas células dentro do retângulo visível por índice `(mode,y,x)`. Comprimimos células adjacentes em faixas, fazemos união topológica com JTS e subtraímos do polígono mundial. Isso evita buracos sobrepostos inválidos. Cálculos e consultas ficam em dispatchers de trabalho; resultados antigos são cancelados ao mudar câmera/modo. A camada de névoa acompanha zoom, rotação e deslocamento do MapLibre.

O zoom mínimo é **2**, máximo 20, para permitir uma visão mais ampla do rastro, e cada geometria de névoa é limitada a **60 mil células**. Em zoom menor que 12 ou ao exceder o limite, o app mantém a névoa e exibe uma cobertura aproximada em verde, com indicação para aproximar; o rastro continua visível acima dela. Essa escolha evita construir uma união mundial enorme. A exploração é suportada entre latitudes -85° e +85°. Nas bordas do antimeridiano, segmentos são separados; esse caso merece validação visual em aparelho. Não há compactação avançada de territórios continentais neste MVP.

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
