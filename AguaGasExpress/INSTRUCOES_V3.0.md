# Água & Gás Express — versão 3.0 (projeto-fonte)

## Alterações incluídas no código
- Campo separado para número da casa e pesquisa automática de CEP pelo ViaCEP, com opção de preencher o endereço manualmente.
- Fundo do ícone verde-claro, mantendo o desenho vetorial da gota e da chama.
- Botões para mostrar/ocultar todas as senhas, incluindo login e alteração da senha do entregador.
- Senha inicial do entregador alterada para `16 08 29`; a alteração feita pela Administração é sincronizada pelo documento `appConfig/main`.
- Conversa de texto e áudio associada ao pedido ativo. O áudio é gravado no celular, enviado como Base64 para a subcoleção `orders/{id}/messages` e reproduzido com saída de áudio de mídia/alto-falante. Duração máxima de 30 segundos.
- Aviso falado ao cliente quando o status passa para “Chegou ao endereço”.
- Contagem persistente de garrafões e botijões vendidos no dia e no mês; a transação atualiza os totais e remove o pedido na mesma operação, evitando contar o mesmo pedido duas vezes.
- Pedidos cancelados e concluídos são removidos da lista ativa; conversas associadas são apagadas.
- Removidos da interface os controles de simulação de pedidos e entregas.
- Versão do app definida como 3.0.

## Antes de testar
1. Abra o projeto no Android Studio e deixe o Gradle sincronizar.
2. No Firebase Console, confirme que a autenticação anônima está habilitada.
3. A conversa usa a subcoleção `orders/{orderId}/messages`. As regras atualmente no arquivo `firestore.rules` incluem essa subcoleção; elas precisam ser publicadas no Firestore para a conversa funcionar.
4. **Aviso de segurança:** as regras incluídas são permissivas para teste (qualquer usuário autenticado anonimamente pode ler e escrever pedidos, mensagens e configuração). Não as use em produção com clientes reais. Antes do lançamento, configure autenticação/autorização adequada por função e restrinja o acesso a cada pedido.
5. Teste em dois aparelhos: criar pedido, enviar texto, gravar/enviar áudio, ouvir áudio, marcar chegada, concluir e conferir os totais.

## Limitação desta entrega
O ambiente que preparou este ZIP não conseguiu baixar a distribuição do Gradle por falta de acesso à internet. Portanto, o projeto-fonte foi atualizado, mas não foi possível confirmar uma compilação nem gerar um APK testado nesta sessão.
