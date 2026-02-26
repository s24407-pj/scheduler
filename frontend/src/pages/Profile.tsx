import { useState, useEffect } from 'react';
import api from '../api';

interface UserProfile {
  id: number;
  phoneNumber: string;
  firstName: string;
  lastName: string;
  email: string | null;
}

export default function Profile() {
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [email, setEmail] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  useEffect(() => {
    api.get('/users/me')
      .then((res) => {
        setProfile(res.data);
        setFirstName(res.data.firstName);
        setLastName(res.data.lastName);
        setEmail(res.data.email || '');
      })
      .catch(() => setError('Nie udało się pobrać profilu'))
      .finally(() => setLoading(false));
  }, []);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError('');
    setSuccess('');
    try {
      const res = await api.put('/users/me', {
        firstName: firstName || null,
        lastName: lastName || null,
        email: email || null,
      });
      setProfile(res.data);
      setSuccess('Profil zaktualizowany!');
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd aktualizacji profilu');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="text-center text-gray-400 py-12">Ładowanie...</div>;
  }

  return (
    <div className="max-w-lg mx-auto">
      <h2 className="text-2xl font-bold text-gray-800 mb-6">👤 Mój profil</h2>

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg mb-4">{error}</div>
      )}
      {success && (
        <div className="bg-green-50 border border-green-200 text-green-700 px-4 py-3 rounded-lg mb-4">{success}</div>
      )}

      <div className="bg-white rounded-2xl shadow-xl p-6">
        <div className="mb-4 text-sm text-gray-500">
          Telefon: <span className="font-medium text-gray-800">{profile?.phoneNumber}</span>
        </div>

        <form onSubmit={handleSave} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Imię</label>
            <input
              type="text"
              value={firstName}
              onChange={(e) => setFirstName(e.target.value)}
              className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Nazwisko</label>
            <input
              type="text"
              value={lastName}
              onChange={(e) => setLastName(e.target.value)}
              className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Email</label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="opcjonalnie"
              className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
            />
          </div>
          <button
            type="submit"
            disabled={saving}
            className="w-full bg-indigo-600 text-white py-2 rounded-lg hover:bg-indigo-700 disabled:opacity-50 transition font-medium"
          >
            {saving ? 'Zapisywanie...' : 'Zapisz zmiany'}
          </button>
        </form>
      </div>
    </div>
  );
}

