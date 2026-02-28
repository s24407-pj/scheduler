import { useState, useEffect } from 'react';
import api from '../api';

// ─── Interfaces ──────────────────────────────────────────────────────────────

interface ScheduleItem {
  id: number;
  customerId: number;
  serviceId: number;
  price: number;
  startTime: string;
  endTime: string;
  status: string;
}

interface ScheduleBlock {
  id: number;
  startTime: string;
  endTime: string;
  reason: string | null;
}

interface Service {
  id: number;
  name: string;
  durationMinutes: number;
  price: number;
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

const statusLabels: Record<string, { label: string; color: string }> = {
  PENDING:   { label: 'Oczekująca',  color: 'bg-yellow-100 text-yellow-800' },
  CONFIRMED: { label: 'Potwierdzona', color: 'bg-blue-100 text-blue-800' },
  CANCELLED: { label: 'Anulowana',   color: 'bg-red-100 text-red-800' },
  COMPLETED: { label: 'Zakończona',  color: 'bg-green-100 text-green-800' },
};

function fmt(iso: string) {
  return new Date(iso).toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit' });
}

function toLocalDatetime(date: string, time: string) {
  return `${date}T${time}`;
}

// ─── Main component ───────────────────────────────────────────────────────────

type Tab = 'reservations' | 'blocks' | 'book';

export default function EmployeeSchedule() {
  const [tab, setTab] = useState<Tab>('reservations');
  const [date, setDate] = useState(() => new Date().toISOString().split('T')[0]);
  const [myId, setMyId] = useState<number | null>(null);

  // Fetch current user's ID once
  useEffect(() => {
    api.get('/users/me').then((r) => setMyId(r.data.id)).catch(() => {});
  }, []);

  return (
    <div className="max-w-3xl mx-auto">
      <h2 className="text-2xl font-bold text-gray-800 mb-4">📅 Harmonogram</h2>

      {/* Date picker — shared across all tabs */}
      <div className="mb-6">
        <label className="block text-sm font-medium text-gray-700 mb-1">Data</label>
        <input
          type="date"
          value={date}
          onChange={(e) => setDate(e.target.value)}
          className="border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
        />
      </div>

      {/* Tabs */}
      <div className="flex gap-1 mb-6 bg-gray-100 p-1 rounded-xl w-fit">
        {([
          ['reservations', '📋 Wizyty'],
          ['blocks',       '🚫 Blokady'],
          ['book',         '➕ Zapisz klienta'],
        ] as [Tab, string][]).map(([id, label]) => (
          <button
            key={id}
            onClick={() => setTab(id)}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition ${
              tab === id ? 'bg-white shadow text-indigo-700' : 'text-gray-600 hover:text-gray-800'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {tab === 'reservations' && <ReservationsTab date={date} />}
      {tab === 'blocks'       && <BlocksTab date={date} companyId={1} />}
      {tab === 'book'         && <BookForClientTab date={date} myId={myId} />}
    </div>
  );
}

// ─── Tab 1: Reservations ─────────────────────────────────────────────────────

function ReservationsTab({ date }: { date: string }) {
  const [schedule, setSchedule] = useState<ScheduleItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const fetch = async () => {
    setLoading(true);
    setError('');
    try {
      const res = await api.get('/reservations/employee', {
        params: { start: `${date}T00:00:00`, end: `${date}T23:59:59` },
      });
      setSchedule(res.data);
    } catch {
      setError('Nie udało się pobrać harmonogramu');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetch(); }, [date]);

  const complete = async (id: number) => {
    try {
      await api.patch(`/reservations/${id}/complete`);
      fetch();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd');
    }
  };

  if (loading) return <div className="text-center text-gray-400 py-12">Ładowanie...</div>;

  return (
    <>
      {error && <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg mb-4">{error}</div>}
      {schedule.length === 0 ? (
        <div className="bg-white rounded-2xl shadow-xl p-8 text-center text-gray-400">Brak wizyt na ten dzień</div>
      ) : (
        <div className="space-y-3">
          {schedule.map((item) => {
            const st = statusLabels[item.status] || { label: item.status, color: 'bg-gray-100 text-gray-800' };
            return (
              <div key={item.id} className="bg-white rounded-xl shadow-md p-5 flex items-center justify-between">
                <div>
                  <div className="font-semibold text-gray-800">{fmt(item.startTime)} – {fmt(item.endTime)}</div>
                  <div className="text-sm text-gray-500 mt-1">
                    Klient #{item.customerId} · Usługa #{item.serviceId} · {item.price} zł
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <span className={`px-3 py-1 rounded-full text-xs font-medium ${st.color}`}>{st.label}</span>
                  {item.status === 'PENDING' && (
                    <button
                      onClick={() => complete(item.id)}
                      className="bg-green-500 text-white px-3 py-1 rounded-lg text-sm hover:bg-green-600 transition"
                    >
                      Zakończ
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}

// ─── Tab 2: Schedule blocks ───────────────────────────────────────────────────

function BlocksTab({ date, companyId: _companyId }: { date: string; companyId: number }) {
  const [blocks, setBlocks] = useState<ScheduleBlock[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  // Create form
  const [startTime, setStartTime] = useState('');
  const [endTime, setEndTime] = useState('');
  const [reason, setReason] = useState('');
  const [saving, setSaving] = useState(false);

  const fetchBlocks = async () => {
    setLoading(true);
    try {
      const res = await api.get('/schedule-blocks/employee', {
        params: { start: `${date}T00:00:00`, end: `${date}T23:59:59` },
      });
      setBlocks(res.data);
    } catch {
      setError('Nie udało się pobrać blokad');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setStartTime(`${date}T09:00`);
    setEndTime(`${date}T10:00`);
    fetchBlocks();
  }, [date]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError('');
    setSuccess('');
    try {
      await api.post('/schedule-blocks', {
        startTime,
        endTime,
        reason: reason.trim() || null,
      });
      setSuccess('Blokada dodana');
      setReason('');
      fetchBlocks();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd tworzenia blokady');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: number) => {
    setError('');
    setSuccess('');
    try {
      await api.delete(`/schedule-blocks/${id}`);
      setSuccess('Blokada usunięta');
      fetchBlocks();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd usuwania blokady');
    }
  };

  return (
    <>
      {error   && <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg mb-4">{error}</div>}
      {success && <div className="bg-green-50 border border-green-200 text-green-700 px-4 py-3 rounded-lg mb-4">{success}</div>}

      {/* Create form */}
      <div className="bg-white rounded-2xl shadow-xl p-6 mb-6">
        <h3 className="text-lg font-semibold text-gray-800 mb-4">Dodaj blokadę</h3>
        <form onSubmit={handleCreate} className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Od</label>
              <input
                type="datetime-local"
                value={startTime}
                onChange={(e) => setStartTime(e.target.value)}
                required
                className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Do</label>
              <input
                type="datetime-local"
                value={endTime}
                onChange={(e) => setEndTime(e.target.value)}
                required
                className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
              />
            </div>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Powód (opcjonalnie)</label>
            <input
              type="text"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="np. Przerwa obiadowa"
              className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
            />
          </div>
          <button
            type="submit"
            disabled={saving}
            className="w-full bg-indigo-600 text-white py-2 rounded-lg hover:bg-indigo-700 disabled:opacity-50 transition font-medium"
          >
            {saving ? 'Zapisywanie...' : '🚫 Zablokuj czas'}
          </button>
        </form>
      </div>

      {/* Blocks list */}
      {loading ? (
        <div className="text-center text-gray-400 py-8">Ładowanie...</div>
      ) : blocks.length === 0 ? (
        <div className="bg-white rounded-2xl shadow-xl p-8 text-center text-gray-400">Brak blokad na ten dzień</div>
      ) : (
        <div className="space-y-3">
          {blocks.map((b) => (
            <div key={b.id} className="bg-white rounded-xl shadow-md p-5 flex items-center justify-between">
              <div>
                <div className="font-semibold text-gray-800">{fmt(b.startTime)} – {fmt(b.endTime)}</div>
                {b.reason && <div className="text-sm text-gray-500 mt-1">{b.reason}</div>}
              </div>
              <button
                onClick={() => handleDelete(b.id)}
                className="text-red-500 hover:text-red-700 text-sm font-medium transition"
              >
                Usuń
              </button>
            </div>
          ))}
        </div>
      )}
    </>
  );
}

// ─── Tab 3: Book for client ───────────────────────────────────────────────────

function BookForClientTab({ date, myId }: { date: string; myId: number | null }) {
  const [services, setServices] = useState<Service[]>([]);
  const [selectedService, setSelectedService] = useState<number | null>(null);
  const [slots, setSlots] = useState<string[]>([]);
  const [selectedSlot, setSelectedSlot] = useState<string | null>(null);
  const [slotsLoading, setSlotsLoading] = useState(false);

  const [customerPhone, setCustomerPhone] = useState('');
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');

  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  // Load services
  useEffect(() => {
    api.get('/services/public/company/1')
      .then((r) => {
        setServices(r.data);
        if (r.data.length > 0) setSelectedService(r.data[0].id);
      })
      .catch(() => setError('Nie udało się pobrać usług'));
  }, []);

  // Load available slots when service, date or myId changes
  useEffect(() => {
    if (!selectedService || !date || !myId) return;
    setSlotsLoading(true);
    setSlots([]);
    setSelectedSlot(null);
    api.get('/availability', { params: { employeeId: myId, serviceId: selectedService, date } })
      .then((r) => setSlots(r.data))
      .catch(() => setError('Nie udało się pobrać wolnych terminów'))
      .finally(() => setSlotsLoading(false));
  }, [selectedService, date, myId]);

  const handleBook = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedSlot || !selectedService || !myId) return;
    setSaving(true);
    setError('');
    setSuccess('');
    try {
      await api.post('/reservations/staff', {
        employeeId: myId,
        serviceId: selectedService,
        startTime: toLocalDatetime(date, selectedSlot),
        customerPhone: customerPhone.trim(),
        customerFirstName: firstName.trim() || null,
        customerLastName: lastName.trim() || null,
      });
      setSuccess('Wizyta zapisana! 🎉');
      setSelectedSlot(null);
      setCustomerPhone('');
      setFirstName('');
      setLastName('');
      // Refresh slots
      const r = await api.get('/availability', { params: { employeeId: myId, serviceId: selectedService, date } });
      setSlots(r.data);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd zapisu wizyty');
    } finally {
      setSaving(false);
    }
  };

  const selectedServiceData = services.find((s) => s.id === selectedService);

  return (
    <>
      {error   && <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg mb-4">{error}</div>}
      {success && <div className="bg-green-50 border border-green-200 text-green-700 px-4 py-3 rounded-lg mb-4">{success}</div>}

      <div className="bg-white rounded-2xl shadow-xl p-6 space-y-6">
        {/* Service */}
        <div>
          <h3 className="text-base font-semibold text-gray-800 mb-3">1. Wybierz usługę</h3>
          {services.length === 0 ? (
            <div className="text-gray-400 text-sm">Ładowanie usług...</div>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              {services.map((s) => (
                <button
                  key={s.id}
                  type="button"
                  onClick={() => setSelectedService(s.id)}
                  className={`p-4 rounded-xl border-2 text-left transition ${
                    selectedService === s.id ? 'border-indigo-500 bg-indigo-50' : 'border-gray-200 hover:border-indigo-300'
                  }`}
                >
                  <div className="font-semibold text-gray-800">{s.name}</div>
                  <div className="text-sm text-gray-500">⏱ {s.durationMinutes} min · 💰 {s.price} zł</div>
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Time slot */}
        {selectedService && (
          <div>
            <h3 className="text-base font-semibold text-gray-800 mb-3">2. Wybierz godzinę</h3>
            {slotsLoading ? (
              <div className="text-gray-400 text-sm">Ładowanie terminów...</div>
            ) : slots.length === 0 ? (
              <div className="text-gray-400 text-sm">Brak wolnych terminów na ten dzień</div>
            ) : (
              <div className="grid grid-cols-4 sm:grid-cols-6 gap-2">
                {slots.map((slot) => (
                  <button
                    key={slot}
                    type="button"
                    onClick={() => setSelectedSlot(slot)}
                    className={`py-2 px-3 rounded-lg text-sm font-medium transition ${
                      selectedSlot === slot ? 'bg-indigo-600 text-white' : 'bg-gray-100 text-gray-700 hover:bg-indigo-100'
                    }`}
                  >
                    {slot.substring(0, 5)}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}

        {/* Client data + submit */}
        {selectedSlot && selectedServiceData && (
          <form onSubmit={handleBook} className="space-y-4 border-t border-gray-200 pt-4">
            <h3 className="text-base font-semibold text-gray-800">3. Dane klienta</h3>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Numer telefonu *</label>
              <input
                type="tel"
                value={customerPhone}
                onChange={(e) => setCustomerPhone(e.target.value)}
                placeholder="+48123456789"
                required
                className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
              />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Imię <span className="text-gray-400 font-normal">(nowy klient)</span></label>
                <input
                  type="text"
                  value={firstName}
                  onChange={(e) => setFirstName(e.target.value)}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Nazwisko <span className="text-gray-400 font-normal">(nowy klient)</span></label>
                <input
                  type="text"
                  value={lastName}
                  onChange={(e) => setLastName(e.target.value)}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
                />
              </div>
            </div>
            <p className="text-xs text-gray-400">Imię i nazwisko wymagane tylko jeśli klient nie ma jeszcze konta.</p>

            {/* Summary */}
            <div className="bg-indigo-50 rounded-xl p-4 text-sm space-y-1">
              <p><span className="text-gray-500">Usługa:</span> <span className="font-medium">{selectedServiceData.name}</span></p>
              <p><span className="text-gray-500">Data:</span> <span className="font-medium">{new Date(date).toLocaleDateString('pl-PL', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' })}</span></p>
              <p><span className="text-gray-500">Godzina:</span> <span className="font-medium">{selectedSlot.substring(0, 5)}</span></p>
              <p><span className="text-gray-500">Czas trwania:</span> <span className="font-medium">{selectedServiceData.durationMinutes} min</span></p>
              <p><span className="text-gray-500">Cena:</span> <span className="font-semibold text-indigo-700">{selectedServiceData.price} zł</span></p>
            </div>

            <button
              type="submit"
              disabled={saving}
              className="w-full bg-indigo-600 text-white py-3 rounded-xl hover:bg-indigo-700 disabled:opacity-50 transition font-semibold"
            >
              {saving ? 'Zapisywanie...' : '✅ Zapisz wizytę dla klienta'}
            </button>
          </form>
        )}
      </div>
    </>
  );
}
