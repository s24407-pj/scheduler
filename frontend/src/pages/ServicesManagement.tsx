import { useState, useEffect } from 'react';
import api from '../api';

interface Service {
  id: number;
  companyId: number;
  name: string;
  durationMinutes: number;
  price: number;
  active: boolean;
  createdAt: string;
}

export default function ServicesManagement() {
  const [services, setServices] = useState<Service[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  // Form state
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [name, setName] = useState('');
  const [durationMinutes, setDurationMinutes] = useState('30');
  const [price, setPrice] = useState('50');
  const [saving, setSaving] = useState(false);

  const fetchServices = async () => {
    setLoading(true);
    try {
      const res = await api.get('/services/company/1');
      setServices(res.data);
    } catch {
      setError('Nie udało się pobrać usług');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchServices();
  }, []);

  const resetForm = () => {
    setName('');
    setDurationMinutes('30');
    setPrice('50');
    setEditingId(null);
    setShowForm(false);
  };

  const startEdit = (s: Service) => {
    setName(s.name);
    setDurationMinutes(String(s.durationMinutes));
    setPrice(String(s.price));
    setEditingId(s.id);
    setShowForm(true);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError('');
    setSuccess('');
    try {
      const payload = {
        name,
        durationMinutes: Number(durationMinutes),
        price: Number(price),
      };

      if (editingId) {
        await api.put(`/services/${editingId}`, payload);
        setSuccess('Usługa zaktualizowana!');
      } else {
        await api.post('/services', payload);
        setSuccess('Usługa dodana!');
      }
      resetForm();
      fetchServices();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd zapisywania usługi');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: number) => {
    if (!confirm('Czy na pewno chcesz dezaktywować tę usługę? Istniejące rezerwacje zostaną zachowane.')) return;
    try {
      await api.delete(`/services/${id}`);
      setSuccess('Usługa dezaktywowana');
      fetchServices();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd usuwania');
    }
  };

  if (loading) {
    return <div className="text-center text-gray-400 py-12">Ładowanie...</div>;
  }

  return (
    <div className="max-w-3xl mx-auto">
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-2xl font-bold text-gray-800">💇 Zarządzanie usługami</h2>
        <button
          onClick={() => { resetForm(); setShowForm(!showForm); }}
          className="bg-indigo-600 text-white px-4 py-2 rounded-lg hover:bg-indigo-700 transition font-medium"
        >
          {showForm ? 'Anuluj' : '+ Dodaj usługę'}
        </button>
      </div>

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg mb-4">{error}</div>
      )}
      {success && (
        <div className="bg-green-50 border border-green-200 text-green-700 px-4 py-3 rounded-lg mb-4">{success}</div>
      )}

      {/* Form */}
      {showForm && (
        <div className="bg-white rounded-2xl shadow-xl p-6 mb-6">
          <h3 className="text-lg font-semibold text-gray-800 mb-4">
            {editingId ? 'Edytuj usługę' : 'Nowa usługa'}
          </h3>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Nazwa</label>
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
                className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
              />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Czas trwania (min)</label>
                <input
                  type="number"
                  value={durationMinutes}
                  onChange={(e) => setDurationMinutes(e.target.value)}
                  min="1"
                  max="480"
                  required
                  className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Cena (zł)</label>
                <input
                  type="number"
                  value={price}
                  onChange={(e) => setPrice(e.target.value)}
                  min="0"
                  required
                  className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none"
                />
              </div>
            </div>
            <button
              type="submit"
              disabled={saving}
              className="w-full bg-indigo-600 text-white py-2 rounded-lg hover:bg-indigo-700 disabled:opacity-50 transition font-medium"
            >
              {saving ? 'Zapisywanie...' : editingId ? 'Zaktualizuj' : 'Dodaj usługę'}
            </button>
          </form>
        </div>
      )}

      {/* Services list */}
      {services.length === 0 ? (
        <div className="bg-white rounded-2xl shadow-xl p-8 text-center text-gray-400">
          Brak usług — dodaj pierwszą!
        </div>
      ) : (
        <div className="space-y-3">
          {services.map((s) => (
            <div
              key={s.id}
              className={`bg-white rounded-xl shadow-md p-5 flex items-center justify-between ${
                !s.active ? 'opacity-50' : ''
              }`}
            >
              <div>
                <div className="font-semibold text-gray-800 flex items-center gap-2">
                  {s.name}
                  {!s.active && (
                    <span className="text-xs bg-gray-200 text-gray-600 px-2 py-0.5 rounded-full">Nieaktywna</span>
                  )}
                </div>
                <div className="text-sm text-gray-500">
                  {s.durationMinutes} min · {s.price} zł
                </div>
              </div>
              <div className="flex gap-2">
                {s.active && (
                  <>
                    <button
                      onClick={() => startEdit(s)}
                      className="text-indigo-600 hover:text-indigo-800 text-sm font-medium transition"
                    >
                      Edytuj
                    </button>
                    <button
                      onClick={() => handleDelete(s.id)}
                      className="text-red-500 hover:text-red-700 text-sm font-medium transition"
                    >
                      Usuń
                    </button>
                  </>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

