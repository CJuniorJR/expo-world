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
