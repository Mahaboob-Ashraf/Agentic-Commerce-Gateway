import { CommerceChat } from "@/components/buyer/commerce-chat";

export default async function ConversationPage({ params }: { params: Promise<{ threadId: string }> }) {
  const { threadId } = await params;
  return <CommerceChat initialThreadId={threadId} />;
}
