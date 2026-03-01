import { useState, useEffect } from 'react';
import api from '../api';

interface Category {
  id: number;
  name: string;
}

interface ServiceImage {
  id: number;
  imageUrl: string;
}

interface Service {
  id: number;
  companyId: number;
  name: string;
  durationMinutes: number;
  price: number;
  active: boolean;
  categoryId: number | null;
  createdAt: string;
  images: ServiceImage[];
}

export default function ServicesManagement() {
  const [services, setServices] = useState<Service[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  // Service form state
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [name, setName] = useState('');
  const [durationMinutes, setDurationMinutes] = useState('30');
  const [price, setPrice] = useState('50');
  const [saving, setSaving] = useState(false);

  // Category form state
  const [newCategoryName, setNewCategoryName] = useState('');
  const [savingCategory, setSavingCategory] = useState(false);

  const fetchAll = async () => {
    setLoading(true);
    try {
      const [servicesRes, categoriesRes] = await Promise.all([
        api.get('/services/company/1'),
        api.get('/categories'),
      ]);
      setServices(servicesRes.data);
      setCategories(categoriesRes.data);
    } catch {
      setError('Nie udało się pobrać danych');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAll();
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
      fetchAll();
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
      fetchAll();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd usuwania');
    }
  };

  const handleActivate = async (id: number) => {
    try {
      await api.patch(`/services/${id}/activate`);
      setSuccess('Usługa aktywowana');
      fetchAll();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd aktywacji');
    }
  };

  const handleAssignCategory = async (serviceId: number, categoryId: number | null) => {
    try {
      await api.patch(`/services/${serviceId}/category`, { categoryId });
      setSuccess('Kategoria przypisana');
      fetchAll();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd przypisywania kategorii');
    }
  };

  const handleAddCategory = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newCategoryName.trim()) return;
    setSavingCategory(true);
    setError('');
    setSuccess('');
    try {
      await api.post('/categories', { name: newCategoryName.trim() });
      setNewCategoryName('');
      setSuccess('Kategoria dodana!');
      fetchAll();
    } catch (err: any) {
      if (err.response?.status === 409) {
        setError('Kategoria o tej nazwie już istnieje');
      } else if (err.response?.status === 403) {
        setError('Brak uprawnień — tylko właściciel może zarządzać kategoriami.');
      } else {
        setError(err.response?.data?.message || 'Błąd dodawania kategorii');
      }
    } finally {
      setSavingCategory(false);
    }
  };

  const handleUploadImage = async (serviceId: number, file: File) => {
    setError('');
    setSuccess('');
    const formData = new FormData();
    formData.append('image', file);
    try {
      await api.post(`/services/${serviceId}/image`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      setSuccess('Zdjęcie dodane!');
      fetchAll();
    } catch (err: any) {
      setError(err.response?.data?.detail || err.response?.data?.message || 'Błąd dodawania zdjęcia');
    }
  };

  const handleDeleteImage = async (serviceId: number, imageId: number) => {
    setError('');
    setSuccess('');
    try {
      await api.delete(`/services/${serviceId}/image/${imageId}`);
      setSuccess('Zdjęcie usunięte');
      fetchAll();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd usuwania zdjęcia');
    }
  };

  const handleDeleteCategory = async (id: number) => {
    if (!confirm('Usunąć kategorię? Usługi przypisane do tej kategorii zostaną bez kategorii.')) return;
    try {
      await api.delete(`/categories/${id}`);
      setSuccess('Kategoria usunięta');
      fetchAll();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Błąd usuwania kategorii');
    }
  };

  const categoryName = (id: number | null) => {
    if (!id) return null;
    return categories.find((c) => c.id === id)?.name ?? null;
  };

  if (loading) {
    return <div className="text-center text-gray-400 py-12">Ładowanie...</div>;
  }

  return (
    <div className="max-w-3xl mx-auto space-y-6">
      <h2 className="text-2xl font-bold text-gray-800">💇 Zarządzanie usługami</h2>

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-lg">{error}</div>
      )}
      {success && (
        <div className="bg-green-50 border border-green-200 text-green-700 px-4 py-3 rounded-lg">{success}</div>
      )}

      {/* Categories section */}
      <div className="bg-white rounded-2xl shadow-xl p-6">
        <h3 className="text-lg font-semibold text-gray-800 mb-3">🏷️ Kategorie</h3>

        {/* Add category form */}
        <form onSubmit={handleAddCategory} className="flex gap-2 mb-4">
          <input
            type="text"
            value={newCategoryName}
            onChange={(e) => setNewCategoryName(e.target.value)}
            placeholder="Nazwa kategorii..."
            minLength={2}
            maxLength={100}
            className="flex-1 border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none text-sm"
          />
          <button
            type="submit"
            disabled={savingCategory || !newCategoryName.trim()}
            className="bg-indigo-600 text-white px-4 py-2 rounded-lg hover:bg-indigo-700 disabled:opacity-50 transition text-sm font-medium"
          >
            + Dodaj
          </button>
        </form>

        {categories.length === 0 ? (
          <p className="text-sm text-gray-400">Brak kategorii — dodaj pierwszą powyżej.</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {categories.map((c) => (
              <span
                key={c.id}
                className="flex items-center gap-1 bg-indigo-50 text-indigo-700 px-3 py-1 rounded-full text-sm font-medium"
              >
                {c.name}
                <button
                  onClick={() => handleDeleteCategory(c.id)}
                  className="ml-1 text-indigo-400 hover:text-red-500 transition font-bold leading-none"
                  title="Usuń kategorię"
                >
                  ×
                </button>
              </span>
            ))}
          </div>
        )}
      </div>

      {/* Services header + add button */}
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold text-gray-800">Usługi</h3>
        <button
          onClick={() => { resetForm(); setShowForm(!showForm); }}
          className="bg-indigo-600 text-white px-4 py-2 rounded-lg hover:bg-indigo-700 transition font-medium text-sm"
        >
          {showForm ? 'Anuluj' : '+ Dodaj usługę'}
        </button>
      </div>

      {/* Service form */}
      {showForm && (
        <div className="bg-white rounded-2xl shadow-xl p-6">
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
              className={`bg-white rounded-xl shadow-md p-5 ${!s.active ? 'opacity-50' : ''}`}
            >
              <div className="flex items-start justify-between gap-4">
                <div className="flex-1 min-w-0">
                  <div className="font-semibold text-gray-800 flex items-center gap-2 flex-wrap">
                    {s.name}
                    {!s.active && (
                      <span className="text-xs bg-gray-200 text-gray-600 px-2 py-0.5 rounded-full">Nieaktywna</span>
                    )}
                    {categoryName(s.categoryId) && (
                      <span className="text-xs bg-indigo-100 text-indigo-600 px-2 py-0.5 rounded-full">
                        {categoryName(s.categoryId)}
                      </span>
                    )}
                  </div>
                  <div className="text-sm text-gray-500 mt-0.5">
                    {s.durationMinutes} min · {s.price} zł
                  </div>
                  {/* Category selector */}
                  {s.active && (
                    <div className="mt-2 flex items-center gap-2">
                      <label className="text-xs text-gray-500">Kategoria:</label>
                      <select
                        value={s.categoryId ?? ''}
                        onChange={(e) =>
                          handleAssignCategory(s.id, e.target.value ? Number(e.target.value) : null)
                        }
                        className="text-xs border border-gray-200 rounded px-2 py-1 focus:ring-1 focus:ring-indigo-400 outline-none bg-white"
                      >
                        <option value="">— brak —</option>
                        {categories.map((c) => (
                          <option key={c.id} value={c.id}>{c.name}</option>
                        ))}
                      </select>
                    </div>
                  )}
                  {/* Images */}
                  {s.images.length > 0 && (
                    <div className="mt-3 flex flex-wrap gap-2">
                      {s.images.map((img) => (
                        <div key={img.id} className="relative group w-16 h-16">
                          <img
                            src={img.imageUrl}
                            alt="zdjęcie usługi"
                            className="w-16 h-16 object-cover rounded-lg border border-gray-200"
                          />
                          <button
                            onClick={() => handleDeleteImage(s.id, img.id)}
                            className="absolute -top-1 -right-1 bg-red-500 text-white rounded-full w-4 h-4 text-xs flex items-center justify-center opacity-0 group-hover:opacity-100 transition leading-none"
                            title="Usuń zdjęcie"
                          >
                            ×
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                  {s.images.length < 5 && s.active && (
                    <label className="mt-2 inline-flex items-center gap-1 text-xs text-indigo-600 hover:text-indigo-800 cursor-pointer transition">
                      <input
                        type="file"
                        accept="image/jpeg,image/png,image/webp"
                        className="hidden"
                        onChange={(e) => {
                          const file = e.target.files?.[0];
                          if (file) handleUploadImage(s.id, file);
                          e.target.value = '';
                        }}
                      />
                      + Dodaj zdjęcie ({s.images.length}/5)
                    </label>
                  )}
                </div>
                <div className="flex gap-2 shrink-0">
                  {s.active ? (
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
                  ) : (
                    <button
                      onClick={() => handleActivate(s.id)}
                      className="text-green-600 hover:text-green-800 text-sm font-medium transition"
                    >
                      Aktywuj
                    </button>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
