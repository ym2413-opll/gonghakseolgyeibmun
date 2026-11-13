import PostalMime from "postal-mime";

const MAX_BODY_CHARS = 100000;

function extractAuthVerdict(authHeader, mechanism) {
  if (!authHeader) return null;
  const match = authHeader.match(new RegExp(`\\b${mechanism}=([a-zA-Z]+)`, "i"));
  return match ? match[1].toLowerCase() : null;
}

function getHeader(message, parsed, name) {
  const fromRaw = message.headers?.get?.(name);
  if (fromRaw) return String(fromRaw);
  const target = name.toLowerCase();
  const hit = parsed?.headers?.find((h) => h.key?.toLowerCase() === target);
  return hit?.value ? String(hit.value) : null;
}

function sanitizeLocalRecipientPrefix(toAddress) {
  const cleaned = String(toAddress || "").trim().toLowerCase();
  const atIndex = cleaned.indexOf("@");
  if (atIndex <= 0) return "";
  return cleaned.slice(0, atIndex);
}

export default {
  async email(message, env, ctx) {
    const allowedPrefix = String(env.ALLOWED_REPLY_PREFIX || "reply").trim().toLowerCase();
    const localPart = sanitizeLocalRecipientPrefix(message.to);
    const tokenPattern = new RegExp(`^${allowedPrefix.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\+[a-f0-9]{16,64}$`);

    if (!tokenPattern.test(localPart)) {
      message.setReject(`Unsupported reply recipient address: ${message.to}`);
      return;
    }

    const webhookUrl = String(env.BACKEND_WEBHOOK_URL || "").trim();
    const webhookSecret = String(env.EMAIL_WEBHOOK_SECRET || "").trim();
    if (!webhookUrl || !webhookSecret) {
      throw new Error("Worker secrets BACKEND_WEBHOOK_URL and EMAIL_WEBHOOK_SECRET must be configured.");
    }

    const parser = new PostalMime();
    const parsed = await parser.parse(message.raw);

    const authResults = getHeader(message, parsed, "authentication-results");
    const headerFrom = parsed.from?.address ? String(parsed.from.address).trim() : "";
    const envelopeFrom = String(message.from || "").trim();
    const effectiveFrom = headerFrom || envelopeFrom;

    const payload = {
      messageId: parsed.messageId || getHeader(message, parsed, "message-id"),
      from: effectiveFrom,
      to: String(message.to || ""),
      subject: parsed.subject || getHeader(message, parsed, "subject") || "",
      textBody: String(parsed.text || "").slice(0, MAX_BODY_CHARS),
      htmlBody: String(parsed.html || "").slice(0, MAX_BODY_CHARS),
      spfResult: extractAuthVerdict(authResults, "spf"),
      dkimResult: extractAuthVerdict(authResults, "dkim"),
      dmarcResult: extractAuthVerdict(authResults, "dmarc"),
      authenticationResults: authResults,
      inReplyTo: parsed.inReplyTo || getHeader(message, parsed, "in-reply-to")
    };

    const res = await fetch(webhookUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Email-Webhook-Secret": webhookSecret
      },
      body: JSON.stringify(payload)
    });

    if (res.ok) {
      const data = await res.json().catch(() => ({}));
      console.log(
        "Email reply forwarded:",
        JSON.stringify({
          to: message.to,
          questionId: data.questionId || null,
          answerId: data.answerId || null,
          alreadyProcessed: Boolean(data.alreadyProcessed)
        })
      );
      return;
    }

    const errJson = await res.json().catch(() => null);
    const errMsg = errJson?.message || `Backend rejected email webhook (${res.status})`;

    // 400/403/404 are permanent validation/auth rejections -> bounce cleanly via SMTP reject.
    // 401/5xx trigger Worker exception so Cloudflare retries after transient outages or secret sync.
    if (res.status === 400 || res.status === 403 || res.status === 404) {
      message.setReject(errMsg);
      return;
    }

    throw new Error(`Retryable webhook failure (${res.status}): ${errMsg}`);
  }
};
