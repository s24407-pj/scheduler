import { useState, useRef, useEffect } from 'react';
import api from '../api';

interface Message {
  sender: 'user' | 'bot';
  text: string;
}

export default function WhatsAppSimulator() {
  const [phone, setPhone] = useState('48123456789');
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const send = async () => {
    const text = input.trim();
    if (!text || loading) return;
    setInput('');
    setError('');
    setMessages(prev => [...prev, { sender: 'user', text }]);
    setLoading(true);
    try {
      const res = await api.post('/whatsapp/simulate', { from: phone, text });
      const botMessages: string[] = res.data.messages;
      if (botMessages.length === 0) {
        setError('Bot nie wysłał żadnej odpowiedzi. Sprawdź czy backend działa z profilem dev (whatsapp.sender=dev).');
      } else {
        setMessages(prev => [
          ...prev,
          ...botMessages.map(m => ({ sender: 'bot' as const, text: m })),
        ]);
      }
    } catch (err: any) {
      const msg = err.response?.data?.message || err.response?.data?.error || 'Błąd połączenia z backendem.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  const reset = async () => {
    try {
      await api.delete(`/whatsapp/simulate/${phone}`);
      setMessages([]);
      setError('');
    } catch {
      setError('Nie udało się zresetować rozmowy.');
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') send();
  };

  return (
    <div className="max-w-xl mx-auto">
      <div className="bg-white rounded-2xl shadow-xl overflow-hidden flex flex-col" style={{ height: '80vh' }}>
        {/* Header */}
        <div className="bg-green-600 text-white px-4 py-3 flex items-center justify-between">
          <div>
            <p className="font-semibold text-lg">WhatsApp Simulator</p>
            <p className="text-xs text-green-100">Dev only — whatsapp.sender=dev</p>
          </div>
          <button
            onClick={reset}
            className="text-xs bg-green-700 hover:bg-green-800 px-3 py-1 rounded-lg transition"
          >
            Resetuj rozmowę
          </button>
        </div>

        {/* Phone field */}
        <div className="bg-green-50 border-b border-green-100 px-4 py-2 flex items-center gap-2">
          <label className="text-xs text-gray-500 whitespace-nowrap">Numer (from):</label>
          <input
            value={phone}
            onChange={e => setPhone(e.target.value)}
            className="text-sm border border-gray-300 rounded px-2 py-1 flex-1 focus:outline-none focus:ring-1 focus:ring-green-400"
            placeholder="48123456789"
          />
        </div>

        {/* Chat area */}
        <div className="flex-1 overflow-y-auto px-4 py-3 bg-[#e5ddd5] space-y-2">
          {messages.length === 0 && (
            <p className="text-center text-sm text-gray-400 mt-8">
              Wyślij wiadomość, żeby rozpocząć rozmowę z botem.
            </p>
          )}
          {messages.map((msg, i) => (
            <div key={i} className={`flex ${msg.sender === 'user' ? 'justify-end' : 'justify-start'}`}>
              <div
                className={`max-w-[75%] px-3 py-2 rounded-lg text-sm whitespace-pre-wrap shadow-sm ${
                  msg.sender === 'user'
                    ? 'bg-[#dcf8c6] text-gray-800 rounded-br-none'
                    : 'bg-white text-gray-800 rounded-bl-none'
                }`}
              >
                {msg.text}
              </div>
            </div>
          ))}
          {loading && (
            <div className="flex justify-start">
              <div className="bg-white text-gray-400 text-sm px-3 py-2 rounded-lg rounded-bl-none shadow-sm">
                ...
              </div>
            </div>
          )}
          <div ref={bottomRef} />
        </div>

        {/* Error */}
        {error && (
          <div className="bg-red-50 border-t border-red-200 text-red-600 text-xs px-4 py-2">
            {error}
          </div>
        )}

        {/* Input */}
        <div className="bg-white border-t border-gray-200 px-3 py-2 flex gap-2">
          <input
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={loading}
            placeholder="Wpisz wiadomość..."
            className="flex-1 border border-gray-300 rounded-full px-4 py-2 text-sm focus:outline-none focus:ring-1 focus:ring-green-400 disabled:opacity-50"
          />
          <button
            onClick={send}
            disabled={loading || !input.trim()}
            className="bg-green-600 hover:bg-green-700 disabled:opacity-40 text-white rounded-full w-10 h-10 flex items-center justify-center transition"
            aria-label="Wyślij"
          >
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" className="w-5 h-5">
              <path d="M3.478 2.405a.75.75 0 00-.926.94l2.432 7.905H13.5a.75.75 0 010 1.5H4.984l-2.432 7.905a.75.75 0 00.926.94 60.519 60.519 0 0018.445-8.986.75.75 0 000-1.218A60.517 60.517 0 003.478 2.405z" />
            </svg>
          </button>
        </div>
      </div>
    </div>
  );
}
