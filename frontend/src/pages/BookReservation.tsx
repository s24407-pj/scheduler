import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../AuthContext';
import api from '../api';

interface Service {
  id: number;
  name: string;
  durationMinutes: number;
  price: number;
}

/**
 * Public page — browse services and available slots WITHOUT login.
 * Login is triggered only when the user wants to confirm a booking.
 */
export default function BookReservation() {
  const { isAuthenticated } = useAuth();
  const navigate = useNavigate();

  const [employeeId, setEmployeeId] = useState('1');
  const [services, setServices] = useState<Service[]>([]);
  const [selectedService, setSelectedService] = useState<number | null>(null);
  const [date, setDate] = useState(() => {
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    return tomorrow.toISOString().split('T')[0];
  });
  const [slots, setSlots] = useState<string[]>([]);
  const [selectedSlot, setSelectedSlot] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [slotsLoading, setSlotsLoading] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  // Load services publicly (no auth required)
  useEffect(() => {
    api.get('/services/public/company/1')
      .then((res) => {
        setServices(res.data);
        if (res.data.length > 0) setSelectedService(res.data[0].id);
      })
      .catch(() => setError('Nie udało się pobrać usług. Sprawdź czy backend działa.'));
  }, []);

  // Load available slots (public endpoint)
  useEffect(() => {
    if (!selectedService || !date || !employeeId) return;
    setSlotsLoading(true);
    setSlots([]);
    setSelectedSlot(null);
    api
      .get('/availability', {
        params: { employeeId, serviceId: selectedService, date },
      })
      .then((res) => setSlots(res.data))
      .catch(() => setError('Nie udało się pobrać dostępnych terminów'))
      .finally(() => setSlotsLoading(false));
  }, [selectedService, date, employeeId]);

  const handleBook = async () => {
    if (!selectedSlot || !selectedService) return;

    // If not logged in, redirect to login with booking data preserved
    if (!isAuthenticated) {
      const bookingData = {
        employeeId: Number(employeeId),
        serviceId: selectedService,
        date,
        slot: selectedSlot,
      };
      sessionStorage.setItem('pendingBooking', JSON.stringify(bookingData));
      navigate('/login');
      return;
    }

    setLoading(true);
    setError('');
    setSuccess('');
    try {
      const startTime = `${date}T${selectedSlot}`;
      await api.post('/reservations', {
        employeeId: Number(employeeId),
        serviceId: selectedService,
        startTime,
      });
      setSuccess('Wizyta zarezerwowana! 🎉');
      setSelectedSlot(null);
      // Refresh slots
      const res = await api.get('/availability', {
        params: { employeeId, serviceId: selectedService, date },
      });
      setSlots(res.data);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd rezerwacji');
    } finally {
      setLoading(false);
    }
  };

  const selectedServiceData = services.find((s) => s.id === selectedService);

  return (
    <div className="max-w-3xl mx-auto">
      <h2 className="text-2xl font-bold text-gray-800 mb-2">📅 Umów wizytę</h2>
      <p className="text-gray-500 mb-6">
        Wybierz usługę i termin. Logowanie wymagane dopiero przy potwierdzeniu rezerwacji.
      </p>

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg mb-4">{error}</div>
      )}
      {success && (
        <div className="bg-green-50 border border-green-200 text-green-700 px-4 py-3 rounded-lg mb-4">{success}</div>
      )}

      <div className="bg-white rounded-2xl shadow-xl p-6 space-y-6">
        {/* Step 1: Choose service */}
        <div>
          <h3 className="text-lg font-semibold text-gray-800 mb-3">1. Wybierz usługę</h3>
          {services.length > 0 ? (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              {services.map((s) => (
                <button
                  key={s.id}
                  onClick={() => setSelectedService(s.id)}
                  className={`p-4 rounded-xl border-2 text-left transition ${
                    selectedService === s.id
                      ? 'border-indigo-500 bg-indigo-50'
                      : 'border-gray-200 hover:border-indigo-300'
                  }`}
                >
                  <div className="font-semibold text-gray-800">{s.name}</div>
                  <div className="text-sm text-gray-500">
                    ⏱ {s.durationMinutes} min · 💰 {s.price} zł
                  </div>
                </button>
              ))}
            </div>
          ) : (
            <div className="text-gray-400 text-sm">Ładowanie usług...</div>
          )}
        </div>

        {/* Step 2: Choose date */}
        {selectedService && (
          <div>
            <h3 className="text-lg font-semibold text-gray-800 mb-3">2. Wybierz datę</h3>
            <div className="flex items-center gap-4">
              <input
                type="date"
                value={date}
                onChange={(e) => setDate(e.target.value)}
                min={new Date().toISOString().split('T')[0]}
                className="border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
              />
              <div className="text-sm text-gray-400">
                <label className="mr-1">Pracownik ID:</label>
                <input
                  type="number"
                  value={employeeId}
                  onChange={(e) => setEmployeeId(e.target.value)}
                  className="border border-gray-300 rounded px-2 py-1 w-16 text-center focus:ring-2 focus:ring-indigo-500 outline-none"
                />
              </div>
            </div>
          </div>
        )}

        {/* Step 3: Choose time */}
        {selectedService && (
          <div>
            <h3 className="text-lg font-semibold text-gray-800 mb-3">3. Wybierz godzinę</h3>
            {slotsLoading ? (
              <div className="text-gray-400">Ładowanie dostępnych terminów...</div>
            ) : slots.length === 0 ? (
              <div className="text-gray-400">Brak dostępnych terminów na wybrany dzień</div>
            ) : (
              <div className="grid grid-cols-4 sm:grid-cols-6 gap-2">
                {slots.map((slot) => (
                  <button
                    key={slot}
                    onClick={() => setSelectedSlot(slot)}
                    className={`py-2 px-3 rounded-lg text-sm font-medium transition ${
                      selectedSlot === slot
                        ? 'bg-indigo-600 text-white'
                        : 'bg-gray-100 text-gray-700 hover:bg-indigo-100'
                    }`}
                  >
                    {slot.substring(0, 5)}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}

        {/* Summary + Book */}
        {selectedSlot && selectedServiceData && (
          <div className="border-t border-gray-200 pt-4">
            <h3 className="text-lg font-semibold text-gray-800 mb-3">📝 Podsumowanie</h3>
            <div className="bg-indigo-50 rounded-xl p-4 mb-4 space-y-1 text-sm">
              <p><span className="text-gray-500">Usługa:</span> <span className="font-medium">{selectedServiceData.name}</span></p>
              <p><span className="text-gray-500">Data:</span> <span className="font-medium">{new Date(date).toLocaleDateString('pl-PL', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' })}</span></p>
              <p><span className="text-gray-500">Godzina:</span> <span className="font-medium">{selectedSlot.substring(0, 5)}</span></p>
              <p><span className="text-gray-500">Czas trwania:</span> <span className="font-medium">{selectedServiceData.durationMinutes} min</span></p>
              <p><span className="text-gray-500">Cena:</span> <span className="font-semibold text-indigo-700">{selectedServiceData.price} zł</span></p>
            </div>

            <button
              onClick={handleBook}
              disabled={loading}
              className="w-full bg-indigo-600 text-white py-3 rounded-xl hover:bg-indigo-700 disabled:opacity-50 transition font-semibold text-lg"
            >
              {loading
                ? 'Rezerwowanie...'
                : isAuthenticated
                  ? '✅ Potwierdź rezerwację'
                  : '🔐 Zaloguj się i zarezerwuj'}
            </button>

            {!isAuthenticated && (
              <p className="text-xs text-gray-400 text-center mt-2">
                Po zalogowaniu rezerwacja zostanie automatycznie dokończona
              </p>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
