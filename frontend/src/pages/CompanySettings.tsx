import { useState, useEffect } from 'react';
import api from '../api';

interface CompanySettings {
  id: number;
  openingTime: string;
  closingTime: string;
  slotIntervalMinutes: number;
  maxNoShows: number;
  lastMinuteDiscountPercent: number;
  lastMinuteDiscountHours: number;
}

/** Converts "HH:mm:ss" to "HH:mm" for HTML time inputs. */
function toTimeInput(t: string): string {
  return t ? t.slice(0, 5) : '';
}

/** Converts "HH:mm" from HTML time inputs back to "HH:mm:ss". */
function fromTimeInput(t: string): string {
  return t ? `${t}:00` : '';
}

export default function CompanySettings() {
  const [settings, setSettings] = useState<CompanySettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const [openingTime, setOpeningTime] = useState('');
  const [closingTime, setClosingTime] = useState('');
  const [slotInterval, setSlotInterval] = useState('30');
  const [maxNoShows, setMaxNoShows] = useState('3');
  const [discountPercent, setDiscountPercent] = useState('0');
  const [discountHours, setDiscountHours] = useState('24');

  useEffect(() => {
    api.get('/company/settings')
      .then((res) => {
        const s: CompanySettings = res.data;
        setSettings(s);
        setOpeningTime(toTimeInput(s.openingTime));
        setClosingTime(toTimeInput(s.closingTime));
        setSlotInterval(String(s.slotIntervalMinutes));
        setMaxNoShows(String(s.maxNoShows));
        setDiscountPercent(String(s.lastMinuteDiscountPercent));
        setDiscountHours(String(s.lastMinuteDiscountHours));
      })
      .catch(() => setError('Nie udało się pobrać ustawień firmy'))
      .finally(() => setLoading(false));
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError('');
    setSuccess('');
    try {
      const res = await api.put('/company/settings', {
        openingTime: fromTimeInput(openingTime),
        closingTime: fromTimeInput(closingTime),
        slotIntervalMinutes: Number(slotInterval),
        maxNoShows: Number(maxNoShows),
        lastMinuteDiscountPercent: Number(discountPercent),
        lastMinuteDiscountHours: Number(discountHours),
      });
      setSettings(res.data);
      setSuccess('Ustawienia zapisane!');
    } catch (err: any) {
      const msg = err.response?.data?.message;
      if (err.response?.status === 403) {
        setError('Brak uprawnień — tylko właściciel może zmieniać ustawienia.');
      } else if (err.response?.status === 400) {
        setError(msg || 'Nieprawidłowe godziny — godzina zamknięcia musi być późniejsza niż otwarcia.');
      } else {
        setError(msg || 'Błąd zapisywania ustawień');
      }
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="text-center text-gray-400 py-12">Ładowanie...</div>;
  }

  return (
    <div className="max-w-xl mx-auto">
      <h2 className="text-2xl font-bold text-gray-800 mb-6">⚙️ Ustawienia firmy</h2>

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg mb-4">{error}</div>
      )}
      {success && (
        <div className="bg-green-50 border border-green-200 text-green-700 px-4 py-3 rounded-lg mb-4">{success}</div>
      )}

      <div className="bg-white rounded-2xl shadow-xl p-6">
        <form onSubmit={handleSubmit} className="space-y-5">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Godzina otwarcia</label>
              <input
                type="time"
                value={openingTime}
                onChange={(e) => setOpeningTime(e.target.value)}
                required
                className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Godzina zamknięcia</label>
              <input
                type="time"
                value={closingTime}
                onChange={(e) => setClosingTime(e.target.value)}
                required
                className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
              />
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Interwał slotów (minuty)
            </label>
            <input
              type="number"
              value={slotInterval}
              onChange={(e) => setSlotInterval(e.target.value)}
              min="5"
              max="240"
              required
              className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
            />
            <p className="text-xs text-gray-500 mt-1">Min. 5 minut, maks. 240 minut</p>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Próg nieobecności (auto-blokada)
            </label>
            <input
              type="number"
              value={maxNoShows}
              onChange={(e) => setMaxNoShows(e.target.value)}
              min="0"
              required
              className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
            />
            <p className="text-xs text-gray-500 mt-1">
              Po ilu nieobecnościach klient jest automatycznie blokowany. Wpisz 0, aby wyłączyć.
            </p>
          </div>

          <div className="border-t border-gray-100 pt-4">
            <h3 className="text-sm font-semibold text-gray-700 mb-3">Rabat last-minute</h3>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Rabat (%)</label>
                <input
                  type="number"
                  value={discountPercent}
                  onChange={(e) => setDiscountPercent(e.target.value)}
                  min="0"
                  max="100"
                  required
                  className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
                />
                <p className="text-xs text-gray-500 mt-1">Wpisz 0, aby wyłączyć.</p>
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Okno (godziny)</label>
                <input
                  type="number"
                  value={discountHours}
                  onChange={(e) => setDiscountHours(e.target.value)}
                  min="1"
                  max="168"
                  required
                  className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
                />
                <p className="text-xs text-gray-500 mt-1">Rabat dla slotów w ciągu ilu godzin od teraz.</p>
              </div>
            </div>
          </div>

          <button
            type="submit"
            disabled={saving}
            className="w-full bg-indigo-600 text-white py-2 rounded-lg hover:bg-indigo-700 disabled:opacity-50 transition font-medium"
          >
            {saving ? 'Zapisywanie...' : 'Zapisz ustawienia'}
          </button>
        </form>

        {settings && (
          <div className="mt-5 pt-5 border-t border-gray-100 text-sm text-gray-500">
            Aktualne: {settings.openingTime.slice(0, 5)} – {settings.closingTime.slice(0, 5)},
            interwał {settings.slotIntervalMinutes} min,
            próg nieobecności: {settings.maxNoShows === 0 ? 'wyłączony' : settings.maxNoShows},{' '}
            rabat last-minute: {settings.lastMinuteDiscountPercent === 0 ? 'wyłączony' : `${settings.lastMinuteDiscountPercent}% / ${settings.lastMinuteDiscountHours}h`}
          </div>
        )}
      </div>
    </div>
  );
}
