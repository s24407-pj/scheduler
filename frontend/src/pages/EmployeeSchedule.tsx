import { useState, useEffect } from 'react';
import api from '../api';

interface ScheduleItem {
  id: number;
  customerId: number;
  serviceId: number;
  price: number;
  startTime: string;
  endTime: string;
  status: string;
  createdAt: string;
}

const statusLabels: Record<string, { label: string; color: string }> = {
  PENDING: { label: 'Oczekująca', color: 'bg-yellow-100 text-yellow-800' },
  CONFIRMED: { label: 'Potwierdzona', color: 'bg-blue-100 text-blue-800' },
  CANCELLED: { label: 'Anulowana', color: 'bg-red-100 text-red-800' },
  COMPLETED: { label: 'Zakończona', color: 'bg-green-100 text-green-800' },
};

export default function EmployeeSchedule() {
  const [schedule, setSchedule] = useState<ScheduleItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [date, setDate] = useState(() => new Date().toISOString().split('T')[0]);

  const fetchSchedule = async () => {
    setLoading(true);
    setError('');
    try {
      const start = `${date}T00:00:00`;
      const end = `${date}T23:59:59`;
      const res = await api.get('/reservations/employee', {
        params: { start, end },
      });
      setSchedule(res.data);
    } catch {
      setError('Nie udało się pobrać harmonogramu');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchSchedule();
  }, [date]);

  const completeReservation = async (id: number) => {
    try {
      await api.patch(`/reservations/${id}/complete`);
      fetchSchedule();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd');
    }
  };

  const formatTime = (iso: string) => {
    const d = new Date(iso);
    return d.toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit' });
  };

  return (
    <div className="max-w-3xl mx-auto">
      <h2 className="text-2xl font-bold text-gray-800 mb-6">📅 Harmonogram</h2>

      <div className="mb-6">
        <label className="block text-sm font-medium text-gray-700 mb-1">Data</label>
        <input
          type="date"
          value={date}
          onChange={(e) => setDate(e.target.value)}
          className="border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
        />
      </div>

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg mb-4">{error}</div>
      )}

      {loading ? (
        <div className="text-center text-gray-400 py-12">Ładowanie...</div>
      ) : schedule.length === 0 ? (
        <div className="bg-white rounded-2xl shadow-xl p-8 text-center text-gray-400">
          Brak wizyt na ten dzień
        </div>
      ) : (
        <div className="space-y-3">
          {schedule.map((item) => {
            const status = statusLabels[item.status] || { label: item.status, color: 'bg-gray-100 text-gray-800' };
            return (
              <div key={item.id} className="bg-white rounded-xl shadow-md p-5">
                <div className="flex items-center justify-between">
                  <div>
                    <div className="font-semibold text-gray-800">
                      {formatTime(item.startTime)} – {formatTime(item.endTime)}
                    </div>
                    <div className="text-sm text-gray-500 mt-1">
                      Klient #{item.customerId} · Usługa #{item.serviceId} · {item.price} zł
                    </div>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className={`px-3 py-1 rounded-full text-xs font-medium ${status.color}`}>
                      {status.label}
                    </span>
                    {item.status === 'PENDING' && (
                      <button
                        onClick={() => completeReservation(item.id)}
                        className="bg-green-500 text-white px-3 py-1 rounded-lg text-sm hover:bg-green-600 transition"
                      >
                        Zakończ
                      </button>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

