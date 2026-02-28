import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../AuthContext';
import api from '../api';

export default function CustomerLogin() {
  const [step, setStep] = useState<'phone' | 'verify'>('phone');
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [isNew, setIsNew] = useState(false);
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');
  const [loading, setLoading] = useState(false);
  const { login } = useAuth();
  const navigate = useNavigate();

  const hasPendingBooking = !!sessionStorage.getItem('pendingBooking');

  const requestCode = async () => {
    setError('');
    setLoading(true);
    try {
      await api.post('/auth/request-code', { phoneNumber: phone });
      setInfo('Kod SMS został wysłany! (sprawdź logi backendu w trybie dev)');
      setStep('verify');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd wysyłania kodu');
    } finally {
      setLoading(false);
    }
  };

  const completePendingBooking = async () => {
    const raw = sessionStorage.getItem('pendingBooking');
    if (!raw) return false;
    try {
      const booking = JSON.parse(raw);
      const startTime = `${booking.date}T${booking.slot}`;
      await api.post('/reservations', {
        employeeId: booking.employeeId,
        serviceId: booking.serviceId,
        startTime,
      });
      sessionStorage.removeItem('pendingBooking');
      return true;
    } catch {
      sessionStorage.removeItem('pendingBooking');
      return false;
    }
  };

  const verifyCode = async () => {
    setError('');
    setLoading(true);
    try {
      const payload: any = { phoneNumber: phone, code };
      if (isNew) {
        payload.firstName = firstName;
        payload.lastName = lastName;
      }
      const res = await api.post('/auth/verify-code', payload);
      login(res.data.token, 'customer');

      // Try to complete pending booking
      const booked = await completePendingBooking();
      if (booked) {
        navigate('/my-reservations');
      } else {
        navigate('/my-reservations');
      }
    } catch (err: any) {
      const msg = err.response?.data?.message || 'Błąd weryfikacji kodu';
      if (msg.includes('Imię') || msg.includes('Nazwisko')) {
        setIsNew(true);
        setError('Pierwszy raz? Podaj swoje dane, aby dokończyć rejestrację.');
      } else {
        setError(msg);
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-[80vh] flex items-center justify-center">
      <div className="bg-white rounded-2xl shadow-xl p-8 w-full max-w-md">
        <h2 className="text-2xl font-bold text-gray-800 mb-6 text-center">
          🔐 Logowanie klienta
        </h2>

        {hasPendingBooking && (
          <div className="bg-indigo-50 border border-indigo-200 text-indigo-700 px-4 py-3 rounded-lg mb-4 text-sm">
            📅 Po zalogowaniu Twoja rezerwacja zostanie automatycznie dokończona!
          </div>
        )}

        {error && (
          <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg mb-4">
            {error}
          </div>
        )}
        {info && (
          <div className="bg-blue-50 border border-blue-200 text-blue-700 px-4 py-3 rounded-lg mb-4">
            {info}
          </div>
        )}

        {step === 'phone' && (
          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Numer telefonu</label>
              <input
                type="tel"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                placeholder="+48123456789"
                className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 focus:border-transparent outline-none"
              />
            </div>
            <button
              onClick={requestCode}
              disabled={loading || !phone}
              className="w-full bg-indigo-600 text-white py-2 rounded-lg hover:bg-indigo-700 disabled:opacity-50 transition font-medium"
            >
              {loading ? 'Wysyłanie...' : 'Wyślij kod SMS'}
            </button>
          </div>
        )}

        {step === 'verify' && (
          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Kod SMS</label>
              <input
                type="text"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                placeholder="123456"
                maxLength={6}
                className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 focus:border-transparent outline-none text-center text-2xl tracking-widest"
              />
            </div>

            {isNew && (
              <>
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">Imię</label>
                  <input
                    type="text"
                    value={firstName}
                    onChange={(e) => setFirstName(e.target.value)}
                    className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 focus:border-transparent outline-none"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">Nazwisko</label>
                  <input
                    type="text"
                    value={lastName}
                    onChange={(e) => setLastName(e.target.value)}
                    className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 focus:border-transparent outline-none"
                  />
                </div>
              </>
            )}

            <button
              onClick={verifyCode}
              disabled={loading || !code}
              className="w-full bg-indigo-600 text-white py-2 rounded-lg hover:bg-indigo-700 disabled:opacity-50 transition font-medium"
            >
              {loading ? 'Weryfikacja...' : 'Zweryfikuj kod'}
            </button>

            <button
              onClick={() => { setStep('phone'); setCode(''); setError(''); setInfo(''); setIsNew(false); }}
              className="w-full text-gray-500 hover:text-gray-700 text-sm"
            >
              ← Zmień numer
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
