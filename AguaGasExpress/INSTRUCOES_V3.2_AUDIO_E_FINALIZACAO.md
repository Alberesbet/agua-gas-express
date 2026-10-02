AGUA & GAS EXPRESS — VERSAO 3.3

OBJETIVO DESTA VERSAO
- A conversa do pedido fica somente com mensagens de audio; os controles de texto foram removidos.
- O botão único fica verde para gravar, vermelho durante a gravação e amarelo quando chega um áudio novo. Tocar novamente para parar e enviar; tocar no amarelo para ouvir o áudio recebido. O gravador mostra MM:SS e limita cada áudio a 30 segundos.
- Cliente e administracao acompanham os audios do mesmo pedido pelo Firestore.
- O botao do entregador aparece como FINALIZAR ENTREGA para pedidos em rota ou com chegada registrada.
- Ao finalizar, o aplicativo tenta registrar os totais diarios/mensais e remover o pedido ativo e os audios associados.
- A contagem local respeita a troca de dia e de mes para nao manter os valores do periodo anterior.

CONFIGURACAO NECESSARIA NO FIREBASE
1. No Firebase Console do projeto agua-e-gas-express, confirme que Authentication > Sign-in method > Anonymous esta habilitado.
2. Em Firestore Database > Regras, publique regras que permitam a subcolecao orders/{orderId}/messages/{messageId}. O arquivo firestore.rules deste projeto contém a regra da subcoleção orders/{orderId}/messages/{messageId}; é necessário publicar essas regras no Console Firebase antes de testar.
3. Depois de publicar, abra o app nos dois aparelhos com internet e teste com um pedido real de teste: grave e envie um audio de cada lado; confirme que aparece no outro aparelho e reproduz.

ATENCAO SOBRE SEGURANCA
As regras de teste sao permissivas: qualquer usuario autenticado anonimamente pode ler/alterar os documentos permitidos. Nao use essas regras abertas em producao com clientes reais; regras de producao devem separar clientes, administracao e entregadores.

VALIDACAO DE BUILD
O pacote contém código-fonte Android Studio, não um APK compilado. A tentativa de compilar neste ambiente falhou antes da compilacao porque o Gradle 9.4.1 nao estava em cache e o ambiente nao conseguiu acessar services.gradle.org. Abra o projeto no Android Studio, sincronize o Gradle e compile antes de instalar. Nao considere o app testado em dois aparelhos ate realizar esse teste real.


PUBLICAR AS REGRAS DO FIREBASE
- No Console Firebase, abra Firestore Database > Regras e publique o conteúdo do arquivo firestore.rules incluído no projeto.
- Ou, no terminal do Android Studio/terminal do computador com Firebase CLI conectado ao projeto correto, execute: firebase deploy --only firestore:rules
- O arquivo local não altera automaticamente as regras já publicadas na nuvem. Não use regras permissivas em produção: elas são apenas para validar o fluxo entre os aparelhos.

VERSAO 3.3
- O botão de entrega é mostrado também para pedidos com status Autorizado (Aguardando saída), para não desaparecer antes de o entregador iniciar a rota.
- O botão único de áudio fica amarelo quando detecta uma nova mensagem de voz recebida; ao tocar, reproduz a mensagem mais recente.
