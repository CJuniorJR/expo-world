# Consolidação visual — 0.6.2

## Duas representações independentes

`route_samples` é o histórico cronológico, com chave `(mode, sequence)`. Cada leitura recebida enquanto o registro está ativo é inserida sem redução por tempo/distância e sem o antigo corte de 20 mil amostras. A inserção falha em caso de chave duplicada em vez de sobrescrever dados. Coordenadas, precisão, relógio monotônico e velocidade de entrada permanecem separados da velocidade suavizada usada para cores. Rejeições do filtro têm `accepted=false`; ficam na sequência, mas não contribuem para distância ou geometria. O tempo de apresentação é monotônico mesmo se chegar uma leitura atrasada; o relógio original fica preservado em outra coluna.

`ConsolidatedTrace` é um cache visual em memória, sem referência ao DAO ou permissões de escrita. Trechos têm coordenadas, sequência, velocidade e direção da passagem que os gerou. Inserir uma passagem recorta apenas as partes antigas que ela cobriu. Extremos não repetidos preservam sua cor e sua passagem original. A saída não é usada por `TripSummary`, Room ou cálculo de exploração. Parado sem deslocamento, somente a última aproximação visual fica preta.

O comportamento existente de guardar o último percurso por modo permanece. Salvar com título copia integralmente seus pontos antes de começar outra viagem; consolidação/replay nunca apagam um percurso ou sua cópia. Não há gravação durante pausa nem garantia de registrar após parada forçada. Encerrar drena a fila anterior ao encerramento. Pausar não perde leituras já recebidas/enfileiradas e retomar cria uma ruptura explícita.

O banco 9 acrescenta somente colunas, inclusive às cópias nomeadas, pois usam a mesma tabela com outro `mode`. Dados antigos recebem `accepted=true` e metadados desconhecidos `NULL`. Nenhum ponto antigo é alterado. Amostras descartadas antes desta versão não podem ser recuperadas.

## Correspondência geográfica

O índice mutável Quadtree do JTS consulta somente peças próximas ao novo segmento. A comparação usa Mercator corrigida pelo cosseno da latitude local, em metros no solo, para segmentos curtos. Travessias do antimeridiano, pausas, rejeições e lacunas não criam linhas. Parâmetros editáveis em `TraceVisualConfig`:

| Condição | Regra |
|---|---|
| Alinhamento | Até 10° entre eixos, aceitando ida e volta |
| Sem identificação comum da via | Desvio máximo 1,5 m, limitado pela precisão |
| Mesma via reconhecida na base local | Máximo 5 m, também limitado pela precisão |
| Vias conhecidas distintas | Nunca consolidar |
| Precisão acima de 12 m | Só coincidências de até 10 cm |
| Precisão antiga desconhecida | Tolerância conservadora de 25 cm |
| Cruzamentos | Rejeitar eixos desalinhados e cruzamentos rasantes que troquem de lado |

A identificação de via reaproveita as correspondências conservadoras já calculadas pela base offline de Curitiba, com namespace da versão do catálogo. Não faz consultas online, não altera coordenadas nem concede créditos adicionais. A tolerância usa metade da menor precisão entre os dois segmentos, respeitando os tetos. Somente o intervalo longitudinal realmente coberto é removido: retornos parciais e frequências de amostragem diferentes não apagam o restante da rua.

GPS sozinho não distingue com certeza pistas quase coincidentes, viadutos ou vias mais próximas que seu erro, especialmente fora do catálogo. A estratégia favorece preservar peças em casos sem correspondência segura; pode deixar duplicações em GPS ruidoso, em vez de usar um buffer largo que apagaria ruas vizinhas. Os testes verificam cruzamentos, paralelas a 7 m, vias conhecidas distintas a aproximadamente 1 m e pequenas oscilações sobre a mesma via. Testes de rua continuam necessários para ajustar esses limites.

## Atualização e replay

`TraceRenderTimeline` consome apenas as novas amostras do prefixo cronológico. O histórico inteiro não é reconsolidado a cada atualização. Um pequeno diário de alterações permite sobrepor e desfazer a ponta interpolada do replay: ao avançar, ela vira história confirmada; ao voltar no tempo, só o cache visual é refeito. Prefixo mais ponta usa uma lista imutável sem copiar todos os pontos. Uma única cor e contorno usam a mesma fonte consolidada; as antigas fontes de histórico/cauda/ponta foram removidas. Setas em células espaciais evitam um marcador por leitura.

Quadtree, cortes, serialização e cálculo de setas rodam em `Dispatchers.Default`, com exclusão mútua do cache e cancelamento de resultados obsoletos. Há cache de snapshots/JSON quando a geometria não muda. O [GeoJsonSource do MapLibre](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.style.sources/-geo-json-source/index.html) recebe o resultado na thread da interface. A API usada atualiza a coleção GeoJSON; não alegamos publicação de diferenças por feature. O custo de serializar/publicar uma geometria alterada ainda cresce com o número de trechos visíveis, mas a busca/corte geográfico é incremental e repetir uma rua não acumula suas passagens na geometria.

Replay usa `ReplayTimeline.frame` sobre o histórico original, sem stride, simplificação, ordenação espacial ou deduplicação. Leituras rejeitadas mantêm o intervalo temporal e não movem o marcador para um salto inválido. Todas as passagens válidas e os retornos são reproduzidos. Prévia/PNG/GIF usam a mesma consolidação; o GIF conserva um cache incremental entre quadros. O GIF continua sendo uma amostragem temporal de 100 quadros, portanto uma passagem muito curta pode ocorrer entre quadros; isso não reduz o histórico nem o replay interativo.

O histórico cresce com o tempo da viagem e usa armazenamento/memória proporcional ao número de leituras. O snapshot Room ainda carrega a viagem na memória; não foi acrescentada paginação de viagens de vários dias. As células e o algoritmo de névoa não foram alterados.

## Validação

Automação em `ConsolidatedTraceTest`: percurso simples; ida/volta; duas passagens no mesmo sentido; três passagens; repetição parcial com amostragem diferente; circuito e segunda volta; replay incremental de todos; interpolação, busca para trás e retorno ao fim; pausa/retomada/encerramento; rejeições; precisão e vias próximas; histórico com 20.101 leituras; 100 repetições com geometria limitada ao trecho único. O contador de candidatos verifica que o índice não compara cada ponto com a viagem inteira.

`check_migration_sql.py` valida as dez variantes históricas do schema em SQLite do host, preservação dos dados, novas colunas e consulta de 40.002 pontos com retorno à origem. Não substitui a validação do Room no Android. `PersistenceTest` acrescenta fechar/reabrir, cópia nomeada com repetições/rejeições, proteção contra sobrescrita e preservação além do limite antigo. Compilar seu APK não significa executar esses testes.

Executado nesta entrega: **95 testes JVM, zero falhas**, incluindo 15 testes de consolidação; `assembleDebug`, `lintDebug` e `assembleDebugAndroidTest`; scripts de migração SQLite, integridade da base de Curitiba e assets do APK. O lint termina sem erros, mantendo avisos existentes de APIs depreciadas/atualizações e estilo. `adb devices -l` não encontrou aparelho/emulador: os testes instrumentados foram compilados, mas **não executados**. GPS real, serviço com tela bloqueada e apresentação nativa do MapLibre permanecem na lista manual abaixo.

Checklist no aparelho (atualize o APK sem desinstalar):

1. Em Ajustes, simule o percurso normal de Curitiba. Confira continuidade, temas e névoa.
2. Selecione **Ida e volta • rastro consolidado**. A volta deve substituir progressivamente a ida, com setas no novo sentido ao aproximar o mapa.
3. Pause no meio da volta, mova o mapa e retome. Nenhuma linha deve ligar o intervalo pausado. Encerre também enquanto pausado.
4. Abra o replay, assista à ida e à volta completas e busque instantes antes/depois do retorno. Não pode haver cor ou geometria de uma passagem futura.
5. Confira GIF/PNG e reabra o app: resumo e todas as passagens do replay devem permanecer.
6. Com um passageiro operando o celular, valide duas passagens no mesmo sentido, três passagens, retorno parcial e circuito. O motorista só inicia antes de sair e encerra parado. Compare a distância cronológica, que inclui repetições, com o rastro geográfico único.
7. Valide cruzamentos e ruas paralelas reais, GPS impreciso, túnel e perda de internet; o cache cartográfico pode falhar, mas a gravação continua local.
8. Bloqueie a tela numa viagem real, retome e encerre pela notificação. A simulação roda em primeiro plano e não prova o comportamento do serviço em segundo plano.
