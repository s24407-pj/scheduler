import { useState, useEffect } from 'react';
import api from '../api';

interface Reservation {
  id: number;
  employeeId: number;
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

export default function MyReservations() {
  const [reservations, setReservations] = useState<Reservation[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const fetchReservations = async () => {
    setLoading(true);
    try {
      const res = await api.get('/reservations/me');
      setReservations(res.data);
    } catch {
      setError('Nie udało się pobrać rezerwacji');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchReservations();
  }, []);

  const cancelReservation = async (id: number) => {
    if (!confirm('Czy na pewno chcesz anulować tę wizytę?')) return;
    try {
      await api.patch(`/reservations/${id}/cancel`);
      fetchReservations();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd anulowania');
    }
  };

  const formatDate = (iso: string) => {
    const d = new Date(iso);
    return d.toLocaleDateString('pl-PL', {
      weekday: 'long',
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    });
  };

  const formatTime = (iso: string) => {
    const d = new Date(iso);
    return d.toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit' });
  };

  if (loading) {
    return <div className="text-center text-gray-400 py-12">Ładowanie...</div>;
  }

  return (
    <div className="max-w-3xl mx-auto">
      <h2 className="text-2xl font-bold text-gray-800 mb-6">📋 Moje wizyty</h2>

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg mb-4">{error}</div>
      )}

      {reservations.length === 0 ? (
        <div className="bg-white rounded-2xl shadow-xl p-8 text-center text-gray-400">
          <p className="text-lg">Brak rezerwacji</p>
          <p className="text-sm mt-2">Umów swoją pierwszą wizytę!</p>
        </div>
      ) : (
        <div className="space-y-4">
          {reservations.map((r) => {
            const status = statusLabels[r.status] || { label: r.status, color: 'bg-gray-100 text-gray-800' };
            return (
              <div key={r.id} className="bg-white rounded-xl shadow-md p-5 flex items-center justify-between">
                <div>
                  <div className="font-semibold text-gray-800">
                    {formatDate(r.startTime)}
                  </div>
                  <div className="text-gray-500 text-sm">
                    {formatTime(r.startTime)} – {formatTime(r.endTime)}
                  </div>
                  <div className="text-gray-500 text-sm mt-1">
                    Usługa #{r.serviceId} · {r.price} zł
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <span className={`px-3 py-1 rounded-full text-xs font-medium ${status.color}`}>
                    {status.label}
                  </span>
                  {(r.status === 'PENDING' || r.status === 'CONFIRMED') && (
                    <button
                      onClick={() => cancelReservation(r.id)}
                      className="text-red-500 hover:text-red-700 text-sm font-medium transition"
                    >
                      Anuluj
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

