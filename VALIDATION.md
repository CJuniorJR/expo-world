# Validação — 15/10/2026 — versão 0.4.0

## Verificações executadas

```text
gradlew.bat :core:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL
```

JDK 17, Gradle 8.13, SDK Platform 36, Build Tools 35.0.0. Compilação final: 14 s. Testes JVM executados na primeira compilação desta alteração; a compilação incremental final reutilizou os resultados UP-TO-DATE.

- **47 testes JVM aprovados**, sem falhas/erros/ignorados. Incluem os 40 anteriores, quatro de backup e três de cobertura, pausa e cenários adversos.
- Backup: round-trip vazio e coordenadas negativas; arquivo corrompido, truncado, versão/tamanho de grade incompatíveis, contagem errada, dados extras, linhas grandes/coordenadas impossíveis; streaming de 20 mil células.
- Exploração: destaque por faixas sem preencher lacunas; simulações de túnel, oscilação, salto e recuperação pelo filtro real; pausa curta/longa rompe continuidade e impede linhas artificiais.
- App e APK de testes Android compilados.
- **Lint: 0 erros, 12 avisos**: atualizações de ferramentas/dependências, regra de backup legada e sugestão de SharedPreferences KTX. Há aviso de callback legado de localização no compilador. Nenhuma dependência atualizada nesta versão.
- scripts/check_migration_sql.py executado e aprovado: migrações 1→2→3→4→5 compatíveis com o schema Room 5; células e percursos preservados; destaque antigo vazio; agrupamento correto de coordenadas negativas; paginação de exportação; área sem duplicação, isolamento e rollback.
- A verificação Python usa SQLite do computador e não substitui Room no Android. Schemas/DAOs foram gerados por Room/KSP.
- Assinatura: apksigner verify retornou 0; certificado igual ao APK 0.3.0, permitindo atualização.
- aapt: br.com.expoworld, versionCode 7, versionName 0.4.0, mínimo API 23, alvo API 36.
- git diff --check executado sem erros.

## APK

`dist/expo-world-0.4.0-debug.apk` — 62.138.893 bytes.

SHA-256:

```text
6F45DEA4B97CDB161414AEA093466AEA8B21FED54E26AD28FE2F55CF170201BB
```

Instale como atualização, sem desinstalar. A chave MapTiler permanece no arquivo local ignorado e é usada no APK. Nenhuma chave, APK, backup de usuário ou ferramenta local é versionada.

## Testes Android pendentes

adb devices -l executado nesta rodada: nenhum celular/emulador conectado. **Seis testes instrumentados compilados, mas não executados.** O novo teste verifica destaque de células realmente novas, extensão territorial, preservação de rastro/área durante uma mesclagem, paginação e isolamento/limpeza por modo.

Não houve instalação, abertura, captura Android, benchmark ou teste com sensor real. Precisam de aparelho:

1. Atualização 0.3.0→0.4.0 preservando área e rastro, sem inventar destaque antigo.
2. Exportar pelo seletor Android; restaurar arquivo válido, duplicado, corrompido e cancelado. Conferir permissões URI, provedores de arquivos, pouco espaço e rotação durante operação. Validação de formato e SQL no computador não substitui esse fluxo.
3. Visão geral de regiões pequenas/grandes e desconectadas; texto de cobertura aproximada; retorno ao detalhe; rotação, telas pequenas, fontes ampliadas e antimeridiano.
4. Névoa de 20% a 80%, persistência após reabrir; destaque dourado por dois minutos e reativação manual.
5. Resumo automático após encerrar com dados; sem resumo antigo ao encerrar sem pontos; abertura manual de percurso recuperado.
6. Pausa/continuação pela interface e notificação: parar atualizações, manter a mesma viagem e quebrar o segmento; encerrar enquanto pausado. Conferir fila em processamento e permissões/GPS revogados durante pausa.
7. Todos os cenários simulados; pausa curta/longa. A simulação roda apenas com a tela em primeiro plano e não testa o serviço.
8. Viagem real com tela bloqueada, falta de internet, retorno do sinal e recuperação após morte do processo. Não existe funcionamento prometido após parada forçada.

## Limites

Backup inclui somente exploração real em grade de 10 m, até 5 milhões de células/128 MB. Importação mescla sem apagar células, simulação ou rastro existente e sem atribuir área importada ao último percurso. Não inclui histórico/rastro, preferências, chave ou criptografia. O checksum detecta inconsistência, não autentica a origem. Arquivos contêm lugares visitados e são gravados no destino escolhido pelo usuário. Não há envio automático à rede.

Destaque de novas células existe apenas para viagens iniciadas nesta versão. Na visão ampla, a cobertura verde é uma agregação aproximada e pode incluir espaços não visitados; não é revelação detalhada nem modifica o banco. A névoa precisa permanece limitada a 60 mil células no detalhe. Até 4000 blocos por parte visível (duas perto do antimeridiano); consultas usam agregação SQLite e não carregam todo o território em memória.

Pausa mantém serviço/notificação, remove atualizações do serviço e interrompe continuidade. Leituras já em processamento podem terminar de ser salvas. Reabertura após morte do processo recupera dados em disco, sem retomar a viagem. O tempo entre amostras inclui pausas; o resumo identifica tempo sem registro sem distinguir pausa de falha de sinal.

Rastro limitado a 20 mil amostras por modo; área nova cobre a viagem inteira. Otimização em blocos permanece, sem benchmark Android de FPS/bateria/memória. A experiência visual e o comportamento de fabricante ainda aguardam teste real. Sem mapas offline completos. Não houve consulta HTTP com chave MapTiler no computador nesta entrega.
