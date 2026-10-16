# Validação — 15/10/2026 — versão 0.3.0

## Resultado executado

```text
gradlew.bat :core:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL
```

JDK 17, Gradle 8.13, SDK Platform 36 e Build Tools 35.0.0. A execução final levou 14 s; as compilações incrementais reutilizaram os resultados dos testes JVM executados nesta alteração.

- **40 testes JVM aprovados**, sem falhas/erros/ignorados: 19 de exploração, 11 de velocidade/rastro, 5 de progresso/resumo, 3 de recuperação/divisão do rastro e 2 de sinal/círculo de precisão.
- Novos testes verificam classificação de percurso interrompido, cobertura de todas as arestas ao dividir até 20 mil pontos em blocos, estabilidade do histórico entre blocos, ponta ao vivo limitada e lacunas; qualidade/expiração do GPS; círculo de precisão em metros em diferentes latitudes, fechamento e proteção no antimeridiano.
- App e APK dos testes Android compilados.
- **Lint: 0 erros, 11 avisos**. Sem atualização de dependências nesta versão.
- scripts/check_migration_sql.py executado e aprovado: migrações 1→2→3→4 compatíveis com o schema Room 4; exploração positiva/negativa e percurso legado incompleto preservados, área antiga desconhecida, reabertura, isolamento, retenção, novas células sem duplicação e rollback de área/células.
- A verificação Python usa SQLite do computador; não é execução de Room no Android. Room/KSP gerou o DAO com retorno de IDs do INSERT OR IGNORE e transação para células/área/amostra.
- Assinatura do APK verificada com apksigner, retorno 0; mesmo certificado da versão 0.2.0.
- aapt confirmou br.com.expoworld, versionCode 6, versionName 0.3.0, mínimo API 23, alvo API 36.

## APK entregue

`dist/expo-world-0.3.0-debug.apk` — 62.141.356 bytes.

SHA-256:

```text
A4F9F6223C190EBA7F812B7D74F6749162B1CC897037E46B6ADF1C3DA7E958E1
```

Instalar como atualização, sem desinstalar. dist, local.properties, caches e ferramentas estão ignorados pelo Git. A chave do provedor não está no código versionado; o APK usa a configuração local existente.

## Pendente no Android

adb devices -l foi executado nesta entrega e não encontrou dispositivo. **Cinco testes instrumentados foram compilados, mas não executados.** O novo teste verifica percurso incompleto e área nova após reabertura do banco, repetição de células e zeragem da área numa viagem nova.

Não houve abertura/instalação automatizada do aplicativo, captura visual Android ou teste de serviço em runtime. Conferir em aparelho:

1. Atualizar preservando exploração e rastro antigo; área nova antiga deve ser indisponível.
2. Iniciar viagem e conferir precisão em metros no mapa/menu e círculo azul. Perder sinal por mais de 10 s deve remover o círculo e indicar ausência de sinal recente.
3. Conferir rastro e rupturas de sinal ao vivo; usar simulação para comparar trechos rápidos, lentos e parada.
4. Encerrar e consultar área nova; repetir locais deve acrescentar apenas células antes desconhecidas, considerando variação do GPS.
5. Forçar parada durante uma viagem com dados gravados e reabrir: exibir percurso interrompido e trecho salvo, sem reiniciar gravação. Últimas leituras não confirmadas podem faltar.
6. Iniciar nova viagem sem pontos válidos e encerrar: preservar rastro anterior. Primeiro ponto válido de outra viagem substitui o anterior sem conectar os trajetos.
7. Conferir bloqueio da tela, segundo plano, encerramento pela notificação, permissões, GPS desligado e falta de internet.

## Limites conhecidos

A recuperação consulta o rastro persistido, sem retomar o serviço. START_NOT_STICKY permanece. Não há registro após parada forçada nem recuperação de leituras que só estavam em memória. Somente o último percurso de cada modo, até 20 mil amostras; área nova cobre a viagem inteira, mesmo quando o rastro é parcial.

A otimização reduz reconstruções e uploads de geometria da ponta ao vivo; não foi feito benchmark de FPS, memória ou bateria em Android. Snapshots Room e cálculo de paradas ainda percorrem amostras persistidas. O histórico é reconstruído ao fechar bloco, recarregar estilo ou descartar pontos antigos pelo limite.

Precisão é estimativa do sensor; leituras acima de 20 m continuam sem revelar exploração. O círculo não representa probabilidade garantida; é omitido para valores inválidos, acima de 10 km ou cruzando limites geográficos suportados. Simulação não valida GPS real ou segundo plano.

Faixa nominal de 50 m, grade de 10 m projetados, arredondamento conservador, até 60 mil células por viewport e latitude ±85°. Área total/nova são estimativas com correção por latitude. Sem mapas offline completos. Nenhuma consulta HTTP com a chave MapTiler foi executada no computador; carregamento, cotas e restrições dependem de teste no aparelho.
